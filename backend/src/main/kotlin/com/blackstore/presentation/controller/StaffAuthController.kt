package com.blackstore.presentation.controller

import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.identity.*
import com.blackstore.domain.identity.*
import com.blackstore.infrastructure.identity.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
class StaffAuthController(private val login: LoginStaff,private val resolve: ResolveStaffSession,private val logout: LogoutStaff,private val contexts: PreAuthenticationContexts,private val cookies: StaffCookieSettings,private val clock: Clock) {
    @GetMapping("/csrf")
    fun csrf(request: HttpServletRequest,response: HttpServletResponse): ResponseEntity<BaseResponse<CsrfResponse>> {
        val sessionToken=sessionCookie(request,cookies.name)
        if(sessionToken != null) {
            val session=try { resolve.execute(sessionToken) } catch(e: StaffSecurityException) { expire(response); throw e }
            return ResponseEntity.ok(BaseResponse.success(CsrfResponse(session.session.csrfToken,minOf(session.session.expiresAt,clock.instant().plusSeconds(1800))),trace(request)))
        }
        val context=contexts.issue(request.remoteAddr,clock.instant())
        response.addHeader("Set-Cookie",cookie("blackstore-csrf-pre",context.cookie,"/api/v1/auth","Strict",Duration.ofMinutes(5)))
        return ResponseEntity.ok(BaseResponse.success(CsrfResponse(context.csrfToken,context.expiresAt),trace(request)))
    }
    @PostMapping("/login")
    fun login(request: HttpServletRequest,response: HttpServletResponse,@RequestBody body: StaffLoginRequest): ResponseEntity<BaseResponse<StaffLoginResponse>> {
        val existing=sessionCookie(request,cookies.name)
        if(existing!=null) {
            try { resolve.execute(existing) } catch(e: StaffSecurityException) { expire(response); throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID) }
            throw StaffSecurityException(StaffSecurityFailure.ALREADY_AUTHENTICATED)
        }
        assertOrigin(request,cookies)
        val pre=sessionCookie(request,"blackstore-csrf-pre") ?: throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
        val csrf=request.getHeader("X-CSRF-Token") ?: throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
        if(!contexts.consume(pre,csrf,request.remoteAddr,clock.instant())) throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
        response.addHeader("Set-Cookie",cookie("blackstore-csrf-pre","","/api/v1/auth","Strict",Duration.ZERO))
        val issued=login.execute(body.login,body.password.toCharArray(),request.remoteAddr)
        response.addHeader("Set-Cookie",cookie(cookies.name,issued.token,"/","Lax",Duration.ofHours(12)))
        return ResponseEntity.ok(BaseResponse.success(StaffLoginResponse(issued.resolved.staff.toResponse(),issued.resolved.session.csrfToken),trace(request)))
    }
    @GetMapping("/session") fun session(request: HttpServletRequest)=ResponseEntity.ok(BaseResponse.success(StaffSessionResponse(request.staffSession().staff.toResponse()),trace(request)))
    @PostMapping("/logout") fun logout(request: HttpServletRequest,response: HttpServletResponse): ResponseEntity<Void> { logout.execute(request.staffSession()); expire(response); return ResponseEntity.noContent().build() }
    private fun expire(response: HttpServletResponse) { response.addHeader("Set-Cookie",cookie(cookies.name,"","/","Lax",Duration.ZERO)); response.addHeader("Set-Cookie",cookie("blackstore-csrf-pre","","/api/v1/auth","Strict",Duration.ZERO)) }
    private fun cookie(name: String,value: String,path: String,sameSite: String,maxAge: Duration)=ResponseCookie.from(name,value).httpOnly(true).secure(cookies.secure).sameSite(sameSite).path(path).maxAge(maxAge).build().toString()
    private fun trace(request: HttpServletRequest)=request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
}
data class StaffLoginRequest(val login: String,val password: String) { override fun toString()="StaffLoginRequest(redacted)" }
data class StaffResponse(val id: Long,val displayName: String,val role: com.blackstore.domain.cash.StaffRole)
data class StaffSessionResponse(val staff: StaffResponse)
data class StaffLoginResponse(val staff: StaffResponse,val csrfToken: String)
data class CsrfResponse(val csrfToken: String,val expiresAt: Instant)
private fun AuthenticatedStaff.toResponse()=StaffResponse(id.value,displayName,role)
