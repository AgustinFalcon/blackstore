package com.blackstore.domain.pos

import java.util.UUID

data class PosExecutionContext(val clientInstanceId: UUID, val deviceId: String, val terminalId: Long) {
    init { require(deviceId.isNotBlank() && deviceId.length <= 80 && deviceId == deviceId.trim() && terminalId > 0) }
}

sealed interface PosContextResult {
    data class Available(val context: PosExecutionContext) : PosContextResult
    data object Unavailable : PosContextResult
    data object Unknown : PosContextResult
}

/** The only translator for persisted/configured context values. Invalid evidence is never echoed. */
object PosContextTranslator {
    fun fromWire(client: String?, device: String?, terminal: Long?): PosContextResult {
        if (client == null) return PosContextResult.Unknown
        val id = try { UUID.fromString(client) } catch (_: IllegalArgumentException) { return PosContextResult.Unknown }
        if (id.toString() != client || device == null || device.isBlank() || device.length > 80 || device != device.trim() || terminal == null || terminal <= 0)
            return PosContextResult.Unknown
        return PosContextResult.Available(PosExecutionContext(id, device, terminal))
    }
}
