package com.blackstore.presentation.controller

import com.blackstore.domain.reports.*
import com.blackstore.application.dto.accounting.AccountingReportV2ResponseTranslator
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.infrastructure.identity.staffSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.ZoneId

/** Strict request edge: every supplied parameter is accounted for. */
object AccountingReportRequestTranslator {
    fun shift(params: Map<String, String>): AccountingReportRequest {
        require(params.keys == setOf("cashSessionId"))
        return AccountingReportRequest(ReportPeriod.Shift(requireNotNull(params["cashSessionId"]).toLong()))
    }
    fun day(params: Map<String, String>): AccountingReportRequest {
        require(params.keys.all { it in setOf("localDate", "zone", "terminalId", "cashierId", "cashSessionId", "formula", "version") })
        val formula = params["formula"]?.let(FormulaKind::fromWire) ?: FormulaKind.Contribution
        val version = params["version"]?.let(FormulaVersion::fromWire) ?: FormulaVersion.V1
        require(formula != FormulaKind.Unknown && version != FormulaVersion.Unknown)
        return AccountingReportRequest(ReportPeriod.Day(LocalDate.parse(requireNotNull(params["localDate"])), ZoneId.of(requireNotNull(params["zone"]))),
            ReportFilters(params["terminalId"]?.toLong(), params["cashierId"]?.toLong(), params["cashSessionId"]?.toLong()), formula, version)
    }
}
@RestController
@RequestMapping("/api/v2/reports")
class AccountingReportV2Controller(private val queries: ObjectProvider<AccountingReportQuery>) {
    @GetMapping("/shift")
    fun shift(request: HttpServletRequest, @RequestParam params: Map<String, String>) = read(request) { AccountingReportRequestTranslator.shift(params) }
    @GetMapping("/day")
    fun day(request: HttpServletRequest, @RequestParam params: Map<String, String>) = read(request) { AccountingReportRequestTranslator.day(params) }
    private fun read(request: HttpServletRequest, translate: () -> AccountingReportRequest): ResponseEntity<BaseResponse<Any>> {
        val staff = request.staffSession().staff
        val result = try {
            require(request.parameterMap.values.all { it.size == 1 })
            val translated = translate()
            queries.ifAvailable?.read(staff, translated) ?: AccountingReportResult.Rejected(AccountingReportFailure.Unavailable)
        }
        catch (_: RuntimeException) { AccountingReportResult.Rejected(AccountingReportFailure.Validation) }
        val status = (result as? AccountingReportResult.Rejected)?.failure?.let(::httpStatus) ?: 200
        val data: Any = when (result) {
            is AccountingReportResult.Available -> AccountingReportV2ResponseTranslator.translate(result.report)
            is AccountingReportResult.Rejected -> result.failure
        }
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(BaseResponse(status,
            request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(), data,
            if (status == 200) null else "Accounting report unavailable or denied",
            (result as? AccountingReportResult.Rejected)?.failure?.name, if (status == 200) null else false))
    }

    private fun httpStatus(failure: AccountingReportFailure): Int = when (failure) {
        AccountingReportFailure.Validation -> 400
        AccountingReportFailure.Forbidden -> 403
        AccountingReportFailure.NotVisible -> 404
        AccountingReportFailure.Unavailable, AccountingReportFailure.Unknown -> 503
    }
}
