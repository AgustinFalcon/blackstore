package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.domain.port.out.storecore.CatalogItem
import com.blackstore.domain.port.out.storecore.StoreCoreCatalogPort
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import com.blackstore.application.identity.AuthorizeStaffAction
import com.blackstore.domain.identity.StaffPermission
import com.blackstore.infrastructure.identity.staffSession

@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController(
    private val catalogPort: StoreCoreCatalogPort,
    private val authorization: AuthorizeStaffAction,
) {
    @GetMapping
    fun current(request: HttpServletRequest): ResponseEntity<BaseResponse<CatalogResponse>> {
        authorization.permission(request.staffSession().staff, StaffPermission.CatalogRead)
        val snapshot = catalogPort.currentSnapshot()
        val body =
            CatalogResponse(
                version = snapshot?.version,
                importedAt = snapshot?.importedAt,
                validUntil = snapshot?.validUntil,
                stale = snapshot?.stale ?: true,
                canonicalPath = snapshot?.contract?.canonicalPath,
                items = snapshot?.items ?: emptyList(),
            )
        return ResponseEntity.ok(BaseResponse.success(body, traceId(request)))
    }

    private fun traceId(request: HttpServletRequest): String =
        request.getHeader(GlobalExceptionHandler.TRACE_HEADER)?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
}

data class CatalogResponse(
    val version: String?,
    val importedAt: Instant?,
    val validUntil: Instant?,
    val stale: Boolean,
    val canonicalPath: String?,
    val items: List<CatalogItem> = emptyList(),
)
