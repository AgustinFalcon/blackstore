package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.AuthenticatedStaff
import com.blackstore.domain.identity.StaffUserId
import com.blackstore.domain.ledger.ExpenseRecord
import com.blackstore.domain.sales.PaymentMethod
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/** Process-only test failpoint. No production flag, endpoint or dependency can activate it. */
object DurableCashCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val source=PGSimpleDataSource().apply { setURL(args[0]);user="dct_runtime";password="dct_test_only";applicationName=args[6] }
        val boundary=CashCrashBoundary.fromWire(args[1])
        require(boundary != CashCrashBoundary.UNKNOWN)
        val precommit=boundary==CashCrashBoundary.BEFORE_COMMIT
        val barrierSource=object : DataSource by source {
            override fun getConnection(): Connection {
                val connection=source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _,method,values ->
                    if(method.name=="commit" && precommit) barrier()
                    val result=try { method.invoke(connection,*(values ?: emptyArray())) } catch(error: InvocationTargetException) { throw error.targetException }
                    if(method.name=="commit" && !precommit) barrier()
                    result
                } as Connection
            }
        }
        val staff=AuthenticatedStaff(StaffUserId(args[3].toLong()),"Child operator",StaffRole.CASHIER)
        val commands=JdbcCashMutationCommands(barrierSource)
        val now=Instant.parse("2026-10-06T12:00:01Z")
        when(CashCrashOperation.fromWire(args[2])) {
            CashCrashOperation.OPEN -> commands.open(staff,args[4].toLong(),staff.id.value,BigDecimal.ZERO,null,now).recordOrThrow()
            CashCrashOperation.CLOSE -> commands.close(staff,args[5].toLong(),BigDecimal.TEN,null,now).recordOrThrow()
            CashCrashOperation.EXPENSE -> commands.expense(staff,ExpenseRecord(0,args[5].toLong(),"supplies",BigDecimal.ONE,"crash test",PaymentMethod.CASH,staff.id.value,now)).recordOrThrow()
            CashCrashOperation.UNKNOWN -> error("unknown test operation")
        }
    }
    private fun barrier() { println("DCT_READY");System.out.flush();System.`in`.read() }
}
enum class CashCrashBoundary { BEFORE_COMMIT, AFTER_COMMIT, UNKNOWN;
    companion object { fun fromWire(raw: String?)=entries.firstOrNull { it.name==raw } ?: UNKNOWN }
}
enum class CashCrashOperation { OPEN, CLOSE, EXPENSE, UNKNOWN;
    companion object { fun fromWire(raw: String?)=entries.firstOrNull { it.name==raw } ?: UNKNOWN }
}
