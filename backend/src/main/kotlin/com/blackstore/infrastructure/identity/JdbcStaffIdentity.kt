package com.blackstore.infrastructure.identity

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** All identity operations execute as the restricted application role, never as the migration owner. */
class JdbcStaffIdentity(private val source: DataSource, private val tokens: SessionTokenGenerator) : StaffIdentityRepository {
    private fun <T> transaction(block: (Connection) -> T): T = source.connection.use { c ->
        c.autoCommit = false
        try { c.createStatement().use { it.execute("SET LOCAL ROLE blackstore_app") }; val result = block(c); c.commit(); result }
        catch (e: Exception) { c.rollback(); throw e }
    }
    private fun user(rows: ResultSet) = StaffUser(StaffUserId(rows.getLong("id")), rows.getString("login"), rows.getString("display_name"), StaffRole.fromWire(rows.getString("role_code")), if (rows.getBoolean("active")) StaffAccountState.ACTIVE else StaffAccountState.INACTIVE, rows.getString("password_hash"), rows.getLong("credential_version"))
    override fun findByLogin(login: String): StaffUser? = transaction { c -> c.prepareStatement("SELECT * FROM staff_users WHERE lower(login)=?").use { s -> s.setString(1, login); s.executeQuery().use { r -> if (!r.next()) null else user(r).takeUnless { r.next() } } } }
    override fun findById(id: StaffUserId): StaffUser? = transaction { c -> c.prepareStatement("SELECT * FROM staff_users WHERE id=?").use { s -> s.setLong(1, id.value); s.executeQuery().use { r -> if (r.next()) user(r) else null } } }
    override fun create(session: StaffSession) { transaction { c -> insertSession(c,session) } }
    override fun createIfCredentialCurrent(session: StaffSession, verified: StaffUser): Boolean = transaction { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use { s -> s.setString(1,"staff-credential:${verified.id.value}"); s.execute() }
        c.prepareStatement("SELECT * FROM staff_users WHERE id=?").use { s ->
            s.setLong(1,verified.id.value)
            val current=s.executeQuery().use { rows -> if(rows.next()) user(rows) else null }
            if(current == null || current.credentialVersion != verified.credentialVersion || current.passwordHash != verified.passwordHash || current.role != verified.role || current.state != StaffAccountState.ACTIVE || current.role == StaffRole.UNKNOWN) false
            else { insertSession(c,session); true }
        }
    }
    private fun insertSession(c: Connection,session: StaffSession) { c.prepareStatement("INSERT INTO staff_sessions(token_digest,user_id,csrf_token,created_at,last_used_at,expires_at) VALUES (?,?,?,?,?,?)").use { s ->
        s.setString(1, session.digest); s.setLong(2, session.userId.value); s.setString(3, session.csrfToken); s.setTimestamp(4, Timestamp.from(session.createdAt)); s.setTimestamp(5, Timestamp.from(session.lastUsedAt)); s.setTimestamp(6, Timestamp.from(session.expiresAt)); s.executeUpdate()
    } }
    override fun find(digest: String): StaffSession? = transaction { c -> c.prepareStatement("SELECT * FROM staff_sessions WHERE token_digest=?").use { s -> s.setString(1, digest); s.executeQuery().use { r -> if (!r.next()) null else StaffSession(digest, StaffUserId(r.getLong("user_id")), r.getString("csrf_token"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("last_used_at").toInstant(), r.getTimestamp("expires_at").toInstant(), r.getTimestamp("revoked_at")?.toInstant()) } } }
    override fun touchIfActive(digest: String, now: Instant): Boolean = transaction { c -> c.prepareStatement("UPDATE staff_sessions SET last_used_at=GREATEST(last_used_at,?) WHERE token_digest=? AND revoked_at IS NULL AND expires_at>? AND last_used_at>CAST(? AS timestamptz) - interval '30 minutes'").use { s -> s.setTimestamp(1, Timestamp.from(now)); s.setString(2,digest); s.setTimestamp(3,Timestamp.from(now)); s.setTimestamp(4,Timestamp.from(now)); s.executeUpdate() == 1 } }
    override fun revoke(digest: String, now: Instant) { transaction { c -> c.prepareStatement("UPDATE staff_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE token_digest=?").use { s -> s.setTimestamp(1,Timestamp.from(now)); s.setString(2,digest); s.executeUpdate() } } }
    override fun record(event: SecurityAuditEvent, actor: StaffUserId?, target: StaffUserId?) { transaction { c -> c.prepareStatement("INSERT INTO audit_events(actor_id,event_type,aggregate_type,aggregate_id,payload_redacted) VALUES (?,?,'staff',?,'{}'::jsonb)").use { s -> s.setObject(1, actor?.value); s.setString(2,event.name); s.setObject(3,target?.value); s.executeUpdate() } } }
    private fun buckets(login: String, origin: String) = listOf(tokens.digest("login:$login") to 10, tokens.digest("origin:$origin") to 30, tokens.digest("pair:${login.length}:$login:$origin") to 5)
    override fun <T> coordinate(login: String, origin: String, action: () -> T): T = transaction { c ->
        // One credential verification per intersecting bucket; concurrent attempts cannot all pass a stale count.
        // Nonblocking acquisition bounds connection/thread pressure and survives process restart.
        buckets(login,origin).sortedBy { it.first }.forEach { (key,_) ->
            c.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))").use { s -> s.setString(1,key); s.executeQuery().use { r -> r.next(); if(!r.getBoolean(1)) throw StaffSecurityException(StaffSecurityFailure.RATE_LIMITED) } }
        }
        action()
    }
    override fun allowed(login: String, origin: String, now: Instant): Boolean = transaction { c -> buckets(login,origin).all { (key,_) -> c.prepareStatement("SELECT blocked_until FROM staff_login_buckets WHERE bucket_digest=?").use { s -> s.setString(1,key); s.executeQuery().use { r -> !r.next() || r.getTimestamp(1)?.toInstant()?.isAfter(now) != true } } } }
    override fun failure(login: String, origin: String, now: Instant) { transaction { c -> buckets(login,origin).sortedBy { it.first }.forEach { (key,threshold) ->
        c.prepareStatement("INSERT INTO staff_login_buckets(bucket_digest,failures,window_started_at) VALUES (?,0,?) ON CONFLICT DO NOTHING").use { s -> s.setString(1,key); s.setTimestamp(2,Timestamp.from(now)); s.executeUpdate() }
        c.prepareStatement("SELECT failures,window_started_at FROM staff_login_buckets WHERE bucket_digest=? FOR UPDATE").use { s -> s.setString(1,key); s.executeQuery().use { r ->
            check(r.next()); val expired = !now.isBefore(r.getTimestamp(2).toInstant().plusSeconds(900)); val count = if (expired) 1 else r.getInt(1)+1
            val delay = if (count < threshold) 0L else (30L * (1L shl (count-threshold).coerceAtMost(5))).coerceAtMost(900)
            c.prepareStatement("UPDATE staff_login_buckets SET failures=?,window_started_at=?,blocked_until=? WHERE bucket_digest=?").use { u -> u.setInt(1,count); u.setTimestamp(2, if(expired) Timestamp.from(now) else r.getTimestamp(2)); u.setTimestamp(3, if(delay==0L) null else Timestamp.from(now.plusSeconds(delay))); u.setString(4,key); u.executeUpdate() }
        } }
    } } }
    override fun success(login: String, origin: String) { transaction { c -> listOf(buckets(login,origin)[0],buckets(login,origin)[2]).forEach { (key,_) -> c.prepareStatement("UPDATE staff_login_buckets SET failures=0,blocked_until=NULL WHERE bucket_digest=?").use { s -> s.setString(1,key); s.executeUpdate() } } } }
    override fun issue(origin: String, now: Instant): PreAuthenticationContext = transaction { c ->
        val originDigest=tokens.digest(origin)
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use { s -> s.setString(1,originDigest); s.execute() }
        cleanup(c, now)
        c.prepareStatement("SELECT count(*) FROM staff_preauth_contexts WHERE origin_digest=? AND expires_at>?").use { s -> s.setString(1,originDigest); s.setTimestamp(2,Timestamp.from(now)); s.executeQuery().use { r -> r.next(); if(r.getInt(1)>=30) throw StaffSecurityException(StaffSecurityFailure.RATE_LIMITED) } }
        val result=PreAuthenticationContext(tokens.generate(),tokens.generate(),now.plusSeconds(300))
        c.prepareStatement("INSERT INTO staff_preauth_contexts VALUES (?,?,?,?)").use { s -> s.setString(1,tokens.digest(result.cookie)); s.setString(2,tokens.digest(result.csrfToken)); s.setString(3,originDigest); s.setTimestamp(4,Timestamp.from(result.expiresAt)); s.executeUpdate() }; result
    }
    override fun consume(cookie: String, csrf: String, origin: String, now: Instant): Boolean = transaction { c -> c.prepareStatement("DELETE FROM staff_preauth_contexts WHERE context_digest=? AND csrf_digest=? AND origin_digest=? AND expires_at>? RETURNING context_digest").use { s -> s.setString(1,tokens.digest(cookie)); s.setString(2,tokens.digest(csrf)); s.setString(3,tokens.digest(origin)); s.setTimestamp(4,Timestamp.from(now)); s.executeQuery().use { it.next() } } }
    private fun cleanup(c: Connection, now: Instant) { c.prepareStatement("DELETE FROM staff_preauth_contexts WHERE context_digest IN (SELECT context_digest FROM staff_preauth_contexts WHERE expires_at<=? LIMIT 500)").use { s -> s.setTimestamp(1,Timestamp.from(now)); s.executeUpdate() } }
    fun cleanup(now: Instant) { transaction { cleanup(it,now) } }
    private fun owned(sql: String, values: List<Any>): OwnedCashSession? = transaction { c -> c.prepareStatement(sql).use { s -> values.forEachIndexed { i,v -> s.setObject(i+1,v) }; s.executeQuery().use { r -> if(!r.next()) null else OwnedCashSession(r.getLong(1),StaffUserId(r.getLong(2)),com.blackstore.domain.cash.CashSessionStatus.fromWire(r.getString(3))).takeUnless { r.next() } } } }
    override fun cash(id: Long) = owned("SELECT id,cashier_id,status FROM cash_session_projection WHERE id=?",listOf(id))
    private val saleJoin="SELECT c.id,c.cashier_id,c.status FROM sale_intents i JOIN cash_session_projection c ON c.id=i.cash_session_id "
    override fun sale(operationId: String) = runCatching { owned(saleJoin+"WHERE i.operation_id=?",listOf(java.util.UUID.fromString(operationId))) }.getOrNull()
    override fun sale(identity: OperationQuadruple) = runCatching { owned(saleJoin+"WHERE i.client_instance_id=? AND i.device_id=? AND i.sale_id=? AND i.operation_id=?",identity.values()) }.getOrNull()
    override fun payment(paymentId: Long, identity: OperationQuadruple) = runCatching { owned(saleJoin+"JOIN sale_state_projection p ON p.sale_intent_id=i.id JOIN payments pay ON pay.sale_id=p.id WHERE pay.id=? AND i.client_instance_id=? AND i.device_id=? AND i.sale_id=? AND i.operation_id=?",listOf(paymentId)+identity.values()) }.getOrNull()
    override fun eligibleCashier(id: StaffUserId): Boolean = transaction { c -> c.prepareStatement("SELECT count(*) FROM staff_users u WHERE u.id=? AND u.active AND u.role_code='CASHIER' AND NOT EXISTS (SELECT 1 FROM cash_session_projection c WHERE c.cashier_id=u.id AND c.status='OPEN')").use { s -> s.setLong(1,id.value); s.executeQuery().use { r -> r.next(); r.getInt(1)==1 } } }
    private fun OperationQuadruple.values(): List<Any> = listOf(java.util.UUID.fromString(clientInstanceId),deviceId,saleId,java.util.UUID.fromString(operationId))
}
