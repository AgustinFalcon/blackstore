package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.port.out.accounting.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.sql.SQLException
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name=["blackstore.persistence.enabled"],havingValue="true")
class JdbcAccountingLifecycleQuery(private val source: DataSource): AccountingLifecycleQuery {
    override fun observe(staff: AuthenticatedStaff): AccountingLifecycleResult=try {
        source.connection.use { c ->
            c.autoCommit=false;c.isReadOnly=true
            try {
                c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
                val role=c.prepareStatement("SELECT role_code FROM staff_users WHERE id=? AND active").use { s -> s.setLong(1,staff.id.value);s.executeQuery().use { if(it.next()) StaffRole.fromWire(it.getString(1)) else StaffRole.UNKNOWN } }
                if(!StaffAuthorizationPolicy().permits(role,StaffPermission.AccountingRuntimeRead)) throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN)
                val result=c.prepareStatement("SELECT state,accounting_activation_at,clock_timestamp() FROM accounting_runtime WHERE singleton").use { s -> s.executeQuery().use { r ->
                    if(!r.next()) AccountingLifecycleResult.Unknown else runCatching { AccountingLifecycleResult.Observed(AccountingLifecycleObservation(AccountingRuntimeState.fromWire(r.getString(1)),r.getTimestamp(2)?.toInstant(),AccountingContractVersion.V2,r.getTimestamp(3).toInstant())) }.getOrElse { AccountingLifecycleResult.Unknown }
                } }
                c.commit();result
            } catch(e: Exception) { c.rollback();throw e }
        }
    } catch(_: SQLException) { AccountingLifecycleResult.Unavailable }
}
