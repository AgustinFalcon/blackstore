package com.blackstore.infrastructure.persistence

import com.blackstore.domain.accounting.AccountingAdmissionException
import com.blackstore.domain.accounting.AccountingRuntimeState
import com.blackstore.domain.accounting.AccountingWriterAdmissionPolicy
import com.blackstore.domain.accounting.AccountingWriterKind
import java.sql.Connection

/** Shared lifecycle fence precedes cash/sale/delivery locks and is retained through commit.
 * The lifecycle transition trigger takes the exclusive counterpart of this installation key.
 */
class JdbcAccountingLifecycleAdmission {
    fun lock(connection: Connection) {
        check(!connection.autoCommit) { "accounting admission requires a transaction" }
        connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)").use { statement ->
            statement.setLong(1, LIFECYCLE_LOCK_KEY)
            statement.execute()
        }
    }

    fun requireLegacy(connection: Connection) = requireWriter(connection, AccountingWriterKind.Legacy)
    fun requireV2(connection: Connection) = requireWriter(connection, AccountingWriterKind.VersionTwo)
    fun requireWorker(connection: Connection) = requireWriter(connection, AccountingWriterKind.Worker)

    fun state(connection: Connection): AccountingRuntimeState =
        connection.prepareStatement("SELECT state FROM accounting_runtime WHERE singleton").use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) AccountingRuntimeState.Unknown else AccountingRuntimeState.fromWire(rows.getString(1))
            }
        }

    private fun requireWriter(connection: Connection, writer: AccountingWriterKind) {
        lock(connection)
        AccountingWriterAdmissionPolicy().failure(state(connection), writer)?.let { throw AccountingAdmissionException(it) }
    }

    companion object { const val LIFECYCLE_LOCK_KEY: Long = 721455258801L }
}
