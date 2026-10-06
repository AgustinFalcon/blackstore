package com.blackstore.infrastructure.identity

import com.blackstore.application.identity.*
import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.sql.DriverManager

/** A separate local entry point. Runtime DB credentials must never have this role. */
object LocalStaffProvisionTool {
    @JvmStatic fun main(args: Array<String>) {
        val allowed=setOf("--create","--reset","--identifier","--display-name","--role","--protected-stdin")
        require(args.all { !it.startsWith("--") || it in allowed }) { "unsupported option; secrets must never be passed in argv" }
        val console=System.console()
        val protectedInput="--protected-stdin" in args
        require(console!=null || protectedInput) { "a masked console or explicitly protected stdin is required" }
        fun argument(flag: String)=args.indexOf(flag).takeIf { it>=0 }?.let { args.getOrNull(it+1) } ?: error("missing $flag")
        val operation=when { "--create" in args && "--reset" !in args -> StaffProvisionOperation.CREATE; "--reset" in args && "--create" !in args -> StaffProvisionOperation.RESET; else -> error("choose exactly one operation") }
        val identifier=argument("--identifier")
        // Seekable stdin is a regular file; explicit protected-stdin permits pipes/descriptors only.
        if(protectedInput && console==null) {
            val channel=java.io.FileInputStream(java.io.FileDescriptor.`in`).channel
            require(runCatching { channel.position() }.isFailure) { "plain-file stdin is prohibited" }
        }
        val reader=if(protectedInput) System.`in`.bufferedReader() else null
        val confirmation=console?.readLine("Confirm target identifier: ") ?: reader!!.readLine()
        val password=console?.readPassword("Password: ") ?: reader!!.readLine().toCharArray()
        val request=StaffProvisionRequest(operation,identifier,argument("--display-name"),StaffRole.fromWire(argument("--role")),ProcessHandle.current().info().user().orElseThrow { IllegalStateException("OS actor unavailable") })
        val url=System.getenv("BLACKSTORE_PROVISION_DB_URL") ?: error("dedicated provisioning DB URL required")
        val user=System.getenv("BLACKSTORE_PROVISION_DB_USER") ?: error("dedicated provisioning DB user required")
        val dbPassword=console?.readPassword("Database password: ") ?: reader!!.readLine().toCharArray()
        try {
            DriverManager.getConnection(url,user,String(dbPassword)).use { connection ->
                val result=ProvisionLocalStaff(JdbcLocalStaffProvisionPort(connection)).execute(request,confirmation,password)
                println("Staff operation completed for ID ${result.value}")
            }
        } finally { password.fill('\u0000'); dbPassword.fill('\u0000') }
    }
}
class JdbcLocalStaffProvisionPort(private val connection: Connection) : LocalStaffProvisionPort {
    override fun execute(request: StaffProvisionRequest,password: CharArray): StaffUserId {
        connection.autoCommit=false
        try {
            connection.createStatement().use { it.execute("SET LOCAL ROLE blackstore_staff_provisioner") }
            val hash=BCryptPasswordEncoder().encode(String(password))
            val id=when(request.operation) {
                StaffProvisionOperation.CREATE -> connection.prepareStatement("INSERT INTO staff_users(login,display_name,role_code,password_hash,active) VALUES (?,?,?,?,TRUE) RETURNING id").use { s -> s.setString(1,request.identifier.trim().lowercase(java.util.Locale.ROOT)); s.setString(2,request.displayName); s.setString(3,request.role.name); s.setString(4,hash); s.executeQuery().use { r -> check(r.next()); StaffUserId(r.getLong(1)) } }
                StaffProvisionOperation.RESET -> connection.prepareStatement("UPDATE staff_users SET password_hash=?,display_name=?,role_code=?,active=TRUE WHERE id=? RETURNING id").use { s -> s.setString(1,hash); s.setString(2,request.displayName); s.setString(3,request.role.name); s.setLong(4,request.identifier.toLong()); s.executeQuery().use { r -> require(r.next()) { "staff target missing" }; StaffUserId(r.getLong(1)) } }
                StaffProvisionOperation.UNKNOWN -> error("unknown operation")
            }
            val event=if(request.operation==StaffProvisionOperation.CREATE) SecurityAuditEvent.STAFF_CREATED else SecurityAuditEvent.STAFF_RESET
            if(request.operation==StaffProvisionOperation.RESET) connection.prepareStatement("UPDATE staff_sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=?").use { s -> s.setLong(1,id.value); s.executeUpdate() }
            audit(event,request,id)
            connection.commit()
            return id
        } catch(e: Exception) {
            connection.rollback()
            try { connection.createStatement().use { it.execute("SET LOCAL ROLE blackstore_staff_provisioner") }; audit(SecurityAuditEvent.PROVISION_FAILED,request,null); connection.commit() } catch(auditFailure: Exception) { connection.rollback(); e.addSuppressed(auditFailure) }
            // JDBC failure details may include hash values; never print or attach them.
            throw IllegalStateException("Staff provisioning failed; no credential was changed")
        }
    }
    private fun audit(event: SecurityAuditEvent,request: StaffProvisionRequest,id: StaffUserId?) {
        val detail=jacksonObjectMapper().writeValueAsString(mapOf("localActor" to request.localActor,"target" to request.identifier,"operation" to request.operation.name))
        connection.prepareStatement("INSERT INTO audit_events(event_type,aggregate_type,aggregate_id,payload_redacted) VALUES (?,'staff',?,?::jsonb)").use { s -> s.setString(1,event.name); s.setObject(2,id?.value); s.setString(3,detail); s.executeUpdate() }
    }
}
