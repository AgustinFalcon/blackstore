package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.port.out.accounting.AccountingRuntimeQuery
import com.blackstore.domain.sales.PaymentLedgerSemantics
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcAccountingRuntimeQuery(private val source: DataSource) : AccountingRuntimeQuery {
    override fun paymentLedgerSemantics(): PaymentLedgerSemantics = source.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }
            val result = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT state FROM accounting_runtime WHERE singleton").use { rows ->
                    if (!rows.next()) PaymentLedgerSemantics.Unknown else when (AccountingRuntimeState.fromWire(rows.getString(1))) {
                        AccountingRuntimeState.PreActivation -> PaymentLedgerSemantics.LegacyCapturedOnly
                        AccountingRuntimeState.Active, AccountingRuntimeState.Paused -> PaymentLedgerSemantics.NetCapturedAndRefunded
                        AccountingRuntimeState.Unknown -> PaymentLedgerSemantics.Unknown
                    }
                }
            }
            connection.commit()
            result
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }
}
