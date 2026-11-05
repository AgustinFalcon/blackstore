package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.*
import com.blackstore.domain.port.out.storecore.*
import com.blackstore.domain.sales.*
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Test-only process stopped at the physical POSC-B admission commit boundary. */
object SaleAdmissionCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size==5)
        val boundary=SaleAdmissionCrashBoundary.fromWire(args[0]);require(boundary!=SaleAdmissionCrashBoundary.Unknown)
        val source=PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("BLACKSTORE_CRASH_JDBC_URL")));user=requireNotNull(System.getenv("BLACKSTORE_CRASH_JDBC_USER"))
            password=System.getenv("BLACKSTORE_CRASH_JDBC_PASSWORD") ?: "";applicationName="poscb-crash-${args[2]}"
        }
        val barrier=object: DataSource by source {
            override fun getConnection(): Connection {
                val physical=source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _,method,values ->
                    if(method.name=="commit" && boundary==SaleAdmissionCrashBoundary.BeforeCommit) ready()
                    val result=try { method.invoke(physical,*(values ?: emptyArray())) } catch(error: InvocationTargetException) { throw error.targetException }
                    if(method.name=="commit" && boundary==SaleAdmissionCrashBoundary.AfterCommit) ready()
                    result
                } as Connection
            }
        }
        val contract=StoreCoreContractRef(StoreCoreCanonicalContract.CANONICAL_PATH,StoreCoreCanonicalContract.VERSION,StoreCoreCanonicalContract.SHA256)
        val catalog=object: StoreCoreCatalogPort { override fun currentSnapshot()=CatalogSnapshot("catalog",Instant.EPOCH,null,contract,false,listOf(
            CatalogItem("sku-a","Product A","variant-a","price-a",BigDecimal("10.00")),CatalogItem("sku-b","Product B","variant-b","price-b",BigDecimal("7.50")))) }
        val identity=OperationQuadruple(args[1],"device",args[3],args[4])
        fun line(v:String,sku:String,name:String,pv:String,p:String,q:Int)=SaleReservationLine(v,q,pv,TicketLine(sku,name,q,BigDecimal(p),BigDecimal.ZERO,v,pv))
        val command=SaleCommand.ReserveMultiline(UUID.fromString(args[2]),identity,1,listOf(
            line("variant-a","sku-a","Product A","price-a","10.00",2),line("variant-b","sku-b","Product B","price-b","7.50",1)),null)
        val port=JdbcSaleMutationCommands(barrier,catalog,contract.canonicalPath,contract.contractVersion,requireNotNull(contract.openapiDigestSha256),terminalId=1,connectorClientInstanceId=args[1])
        check(port.execute(AuthenticatedSessionContext(AuthenticatedStaff(StaffUserId(1),"Cashier",StaffRole.CASHIER),"d".repeat(64)),command) is SaleCommandResult.Accepted)
    }
    private fun ready() { println("POSCB_CRASH_READY");System.out.flush();System.`in`.read() }
}
enum class SaleAdmissionCrashBoundary { BeforeCommit,AfterCommit,Unknown;
    companion object { fun fromWire(raw:String?)=entries.firstOrNull { it.name==raw } ?: Unknown }
}
