package com.blackstore.infrastructure.persistence

import com.blackstore.domain.pos.*
import java.sql.Connection

/** Immutable process configuration; shares the caller's transaction for new admission. */
class JdbcPosContextReader(private val terminalId: Long, private val connectorClientInstanceId: String) {
    fun read(c: Connection, protectTerminal: Boolean = false): PosContextResult {
        if (terminalId <= 0) return PosContextResult.Unavailable
        if (protectTerminal) c.prepareStatement("SELECT lock_pos_terminal(?)").use { s ->
            s.setLong(1, terminalId); s.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1)) return PosContextResult.Unavailable }
        }
        return c.prepareStatement("SELECT p.client_instance_id,p.device_id,t.id,t.active FROM terminals t JOIN pos_terminal_context p ON p.terminal_id=t.id WHERE t.id=?").use { s ->
            s.setLong(1, terminalId)
            s.executeQuery().use { r ->
                if (!r.next() || !r.getBoolean(4)) return PosContextResult.Unavailable
                val result = PosContextTranslator.fromWire(r.getString(1),r.getString(2),r.getLong(3))
                if (result !is PosContextResult.Available) return PosContextResult.Unavailable
                val connector = PosContextTranslator.fromWire(connectorClientInstanceId,result.context.deviceId,result.context.terminalId)
                if (connector != result) PosContextResult.Unavailable else result
            }
        }
    }
}
