package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.port.out.pos.PosContextQuery
import com.blackstore.domain.pos.PosContextResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.sql.SQLException
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name=["blackstore.persistence.enabled"],havingValue="true")
class JdbcPosContextQuery(private val source: DataSource,
    @Value("\${blackstore.pos.terminal-id:0}") terminalId: Long,
    @Value("\${blackstore.storecore.transport.client-instance-id:}") connectorClientInstanceId: String) : PosContextQuery {
    private val reader = JdbcPosContextReader(terminalId, connectorClientInstanceId)
    override fun observe(staff: AuthenticatedStaff): PosContextResult = try {
        source.connection.use { c ->
            c.autoCommit=false; c.isReadOnly=true; c.transactionIsolation=java.sql.Connection.TRANSACTION_REPEATABLE_READ
            try {
                c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
                val role = c.prepareStatement("SELECT role_code FROM staff_users WHERE id=? AND active").use { s ->
                    s.setLong(1,staff.id.value); s.executeQuery().use { r -> if (r.next()) StaffRole.fromWire(r.getString(1)) else StaffRole.UNKNOWN }
                }
                if (!StaffAuthorizationPolicy().permits(role,StaffPermission.WorkspaceRead)) throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN)
                val result=reader.read(c); c.commit(); result
            } catch (e: Exception) { c.rollback(); throw e }
        }
    } catch (_: SQLException) { PosContextResult.Unavailable }
}
