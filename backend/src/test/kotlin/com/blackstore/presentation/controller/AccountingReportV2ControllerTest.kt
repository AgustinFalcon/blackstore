package com.blackstore.presentation.controller

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.reports.*
import com.blackstore.infrastructure.identity.StaffSessionFilter
import com.blackstore.infrastructure.identity.StaffHttpPermission
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.*

class AccountingReportV2ControllerTest {
    private val staff = AuthenticatedStaff(StaffUserId(7), "Owner", StaffRole.OWNER)
    private val session = ResolvedStaffSession(staff, StaffSession("digest", staff.id, "csrf", Instant.EPOCH, Instant.EPOCH, Instant.MAX, null))
    @Test fun typedDayContractPublishesOneSnapshotAndClosedCompletenessVocabulary() {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val period = ReportPeriod.Day(LocalDate.of(2026, 10, 7), zone)
        val effective = EffectiveReportZone(zone, "zone-v1", Instant.EPOCH)
        val partial = MetricCompleteness(DataCompleteness.Partial, setOf(CompletenessCause.LegacyActivity))
        var requestSeen: AccountingReportRequest? = null
        val query = object : AccountingReportQuery {
            override fun read(actor: AuthenticatedStaff, request: AccountingReportRequest): AccountingReportResult {
                assertEquals(staff, actor); requestSeen = request
                return AccountingReportResult.Available(AccountingReport(period, ReportPeriodResolver().day(period, effective),
                    Instant.parse("2026-10-07T18:00:00Z"), "snapshot-test", effective, 2, true, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, FinancialTotalsPolicy().totals(emptyList()), mapOf(AccountingMetric.NetSales to partial),
                    AccountingFormulaResult(FormulaKind.Contribution, FormulaVersion.V1, BigDecimal.ZERO, partial), null))
            }
        }
        val mvc = MockMvcBuilders.standaloneSetup(AccountingReportV2Controller(StaticListableBeanFactory(mapOf("query" to query)).getBeanProvider(AccountingReportQuery::class.java))).build()
        val response = mvc.perform(get("/api/v2/reports/day").param("localDate", "2026-10-07").param("zone", zone.id).param("cashierId", "4")
            .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(4L, requestSeen?.filters?.cashierId)
        val data = jacksonObjectMapper().readTree(response.contentAsString)["data"]
        assertEquals(ReportPeriodKind.DAY.name, data["periodKind"].asText())
        assertEquals("snapshot-test", data["snapshot"].asText())
        assertEquals(DataCompleteness.Partial.wire, data["completeness"][AccountingMetric.NetSales.wire]["state"].asText())
        assertEquals(FormulaKind.Contribution.wire, data["formula"]["kind"].asText())
        assertTrue(data["totalsByMethod"]["CASH"]["collected"].isNull) // No source evidence cannot become an official zero.
        assertEquals(StaffPermission.DailyReportRead, StaffHttpPermission.permission("GET", "/api/v2/reports/day"))
    }
    @Test fun strictParametersRejectIgnoredAndRepeatedValuesBeforeQuery() {
        var executions = 0
        val query = object : AccountingReportQuery {
            override fun read(staff: AuthenticatedStaff, request: AccountingReportRequest): AccountingReportResult {
                executions++; return AccountingReportResult.Rejected(AccountingReportFailure.NotVisible)
            }
        }
        val mvc = MockMvcBuilders.standaloneSetup(AccountingReportV2Controller(StaticListableBeanFactory(mapOf("query" to query)).getBeanProvider(AccountingReportQuery::class.java))).build()
        assertEquals(400, mvc.perform(get("/api/v2/reports/shift").param("cashSessionId", "1").param("ignored", "x")
            .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response.status)
        assertEquals(400, mvc.perform(get("/api/v2/reports/shift").param("cashSessionId", "1", "2")
            .requestAttr(StaffSessionFilter.SESSION_ATTRIBUTE, session)).andReturn().response.status)
        assertEquals(0, executions)
    }
}
