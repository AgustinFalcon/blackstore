package com.blackstore.infrastructure.identity

import com.blackstore.application.identity.*
import com.blackstore.domain.identity.*
import com.blackstore.domain.model.OperationQuadruple
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.security.SecureRandom
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource
import com.fasterxml.jackson.databind.ObjectMapper

class SecureSessionTokenGenerator : SessionTokenGenerator {
    private val random=SecureRandom()
    override fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
    override fun digest(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
/** There is no in-memory identity fallback in the runtime. */
class UnavailableStaffIdentity : StaffIdentityRepository {
    override fun <T> coordinate(login: String,origin: String,action: () -> T): T = throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE)
    override fun findByLogin(login: String): StaffUser? = null
    override fun findById(id: StaffUserId): StaffUser? = null
    override fun create(session: StaffSession) { throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE) }
    override fun find(digest: String): StaffSession? = null
    override fun touchIfActive(digest: String, now: Instant)=false
    override fun revoke(digest: String, now: Instant) { throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE) }
    override fun record(event: SecurityAuditEvent, actor: StaffUserId?, target: StaffUserId?) = Unit
    override fun allowed(login: String, origin: String, now: Instant)=false
    override fun failure(login: String, origin: String, now: Instant)=Unit
    override fun success(login: String, origin: String)=Unit
    override fun issue(origin: String, now: Instant): PreAuthenticationContext = throw StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE)
    override fun consume(cookie: String, csrf: String, origin: String, now: Instant)=false
    override fun cash(id: Long): OwnedCashSession?=null
    override fun sale(operationId: String): OwnedCashSession?=null
    override fun sale(identity: OperationQuadruple): OwnedCashSession?=null
    override fun payment(paymentId: Long, identity: OperationQuadruple): OwnedCashSession?=null
    override fun eligibleCashier(id: StaffUserId)=false
}
class StaffCookieSettings(val loopback: Boolean, val allowedOrigin: String) {
    val name=if(loopback) "blackstore-session-dev" else "__Host-blackstore-session"
    val secure=!loopback
}
@Configuration
@EnableScheduling
class StaffSecurityConfiguration {
    @Bean fun disabledFrameworkPasswordLogin(): org.springframework.security.core.userdetails.UserDetailsService = org.springframework.security.core.userdetails.UserDetailsService { throw org.springframework.security.core.userdetails.UsernameNotFoundException("Staff authentication uses persisted sessions") }
    @Bean fun staffClock(): Clock=Clock.systemUTC()
    @Bean fun sessionTokenGenerator(): SessionTokenGenerator=SecureSessionTokenGenerator()
    @Bean fun staffCookieSettings(@Value("\${blackstore.identity.loopback-http:false}") loopback: Boolean, @Value("\${server.address:}") address: String, @Value("\${blackstore.identity.allowed-origin:}") origin: String, @Value("\${blackstore.persistence.enabled:false}") identityEnabled: Boolean = false, @Value("\${server.ssl.enabled:false}") tlsEnabled: Boolean = false): StaffCookieSettings {
        require(!loopback || address in setOf("127.0.0.1","::1")) { "HTTP staff cookies require explicit loopback binding" }
        require(!identityEnabled || loopback || tlsEnabled || (address in setOf("127.0.0.1","::1") && origin.startsWith("https://"))) { "Production staff identity requires TLS or an HTTPS origin with a private loopback backend" }
        if(origin.isNotBlank()) { val uri=java.net.URI(origin); require(uri.rawPath.isNullOrEmpty() && uri.rawQuery==null && uri.rawFragment==null && uri.userInfo==null && (uri.scheme=="https" || (loopback && uri.scheme=="http" && uri.host in setOf("localhost","127.0.0.1","::1")))) { "Unsafe staff origin" } }
        return StaffCookieSettings(loopback,origin)
    }
    @Bean fun staffIdentity(source: ObjectProvider<DataSource>, tokens: SessionTokenGenerator): StaffIdentityRepository = source.ifAvailable?.let { JdbcStaffIdentity(it,tokens) } ?: UnavailableStaffIdentity()
    @Bean fun passwordVerifier(): PasswordVerifier { val encoder=BCryptPasswordEncoder(); return object : PasswordVerifier { override fun matches(password: CharArray, hash: String): Boolean = runCatching { encoder.matches(String(password),hash) }.getOrDefault(false) } }
    @Bean fun loginStaff(users: StaffUserRepository,sessions: StaffSessionRepository,tokens: SessionTokenGenerator,passwords: PasswordVerifier,limits: LoginRateLimit,audit: SecurityAuditPort,clock: Clock,coordinator: LoginAttemptCoordinator)=LoginStaff(ValidateLoginAttempt(limits,clock),VerifyStaffCredential(users,passwords),IssueStaffSession(sessions,tokens,clock),RegisterLoginResult(limits,audit,clock),coordinator)
    @Bean fun resolveStaffSession(users: StaffUserRepository,sessions: StaffSessionRepository,tokens: SessionTokenGenerator,clock: Clock)=ResolveStaffSession(users,sessions,tokens,clock)
    @Bean fun logoutStaff(sessions: StaffSessionRepository,audit: SecurityAuditPort,clock: Clock)=LogoutStaff(sessions,audit,clock)
    @Bean fun authorizeStaffAction(ownership: StaffOwnershipQuery,audit: SecurityAuditPort)=AuthorizeStaffAction(ownership,audit)
    @Bean fun staffSessionFilter(resolve: ResolveStaffSession,settings: StaffCookieSettings,mapper: ObjectMapper)=StaffSessionFilter(resolve,settings,mapper)
    @Bean fun filterRegistration(filter: StaffSessionFilter)=FilterRegistrationBean(filter).also { it.isEnabled=false }
    @Bean fun staffSecurityChain(http: HttpSecurity,filter: StaffSessionFilter): SecurityFilterChain {
        http.csrf { it.disable() }.formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }.requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(filter,AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }
    @Bean fun precontextCleanup(identity: StaffIdentityRepository,clock: Clock)=PrecontextCleanup(identity,clock)
}
class PrecontextCleanup(private val identity: StaffIdentityRepository,private val clock: Clock) {
    @Scheduled(initialDelay=60000,fixedDelay=60000) fun run() { (identity as? JdbcStaffIdentity)?.cleanup(clock.instant()) }
}
