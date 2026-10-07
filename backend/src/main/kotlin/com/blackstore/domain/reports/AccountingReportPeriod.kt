package com.blackstore.domain.reports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed class ReportPeriod {
    data class Shift(val sessionId: Long) : ReportPeriod() { init { require(sessionId > 0) } }
    data class Day(val localDate: LocalDate, val zone: ZoneId) : ReportPeriod() {
        init { require(zone.id in ZoneId.getAvailableZoneIds()) { "an installation IANA region is required" } }
    }
    data object Unknown : ReportPeriod()
}

enum class ReportPeriodKind { SHIFT, DAY, Unknown;
    companion object { fun fromWire(value: String?): ReportPeriodKind = entries.firstOrNull { it.name == value } ?: Unknown }
}

data class ReportBounds(val start: Instant, val endExclusive: Instant) {
    init { require(endExclusive > start) }
    operator fun contains(instant: Instant): Boolean = instant >= start && instant < endExclusive
    fun intersects(openedAt: Instant, closedAt: Instant?): Boolean {
        require(closedAt == null || closedAt >= openedAt)
        return openedAt < endExclusive && (closedAt == null || closedAt > start)
    }
}

data class EffectiveReportZone(val zone: ZoneId, val version: String, val effectiveAt: Instant) {
    init { require(version.isNotBlank() && version.length <= 32 && zone.id in ZoneId.getAvailableZoneIds()) }
}

class ReportPeriodResolver {
    fun day(period: ReportPeriod.Day, effectiveZone: EffectiveReportZone): ReportBounds {
        require(period.zone == effectiveZone.zone)
        val start = period.localDate.atStartOfDay(period.zone).toInstant()
        require(effectiveZone.effectiveAt <= start) { "zone version must be effective at period start" }
        return ReportBounds(start, period.localDate.plusDays(1).atStartOfDay(period.zone).toInstant())
    }

    fun shift(period: ReportPeriod.Shift, sessionId: Long, openedAt: Instant, closedAt: Instant?, cutoff: Instant): ReportBounds {
        require(period.sessionId == sessionId && cutoff > openedAt)
        require(closedAt == null || (closedAt > openedAt && closedAt <= cutoff))
        return ReportBounds(openedAt, closedAt ?: cutoff)
    }
}
