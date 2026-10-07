package com.blackstore.infrastructure.identity

import com.blackstore.application.identity.ResolveStaffSession
import com.blackstore.application.dto.response.BaseResponse
import com.blackstore.application.dto.response.HttpCode
import com.blackstore.domain.identity.*
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.UUID

/** Closed route table: unlisted endpoints are private and have zero permissions. */
object StaffHttpPermission {
    fun permission(method: String,path: String): StaffPermission = when {
        method=="OPTIONS" && (path.startsWith("/api/v1/") || path.startsWith("/api/v2/")) -> StaffPermission.CorsPreflight
        method=="GET" && path=="/api/v1/health" -> StaffPermission.PublicHealthRead
        method=="GET" && path=="/api/v1/auth/csrf" -> StaffPermission.CsrfBootstrap
        method=="POST" && path=="/api/v1/auth/login" -> StaffPermission.StaffLogin
        method=="GET" && path=="/api/v1/auth/session" -> StaffPermission.SessionRead
        method=="POST" && path=="/api/v1/auth/logout" -> StaffPermission.StaffLogout
        method=="GET" && path=="/api/v1/catalog" -> StaffPermission.CatalogRead
        method=="GET" && path=="/api/v1/workspace" -> StaffPermission.WorkspaceRead
        method=="GET" && path=="/api/v1/cash-sessions" -> StaffPermission.CashSessionList
        method=="POST" && path=="/api/v1/cash-sessions" -> StaffPermission.CashSessionOpen
        method=="POST" && Regex("/api/v1/cash-sessions/[^/]+/close").matches(path) -> StaffPermission.CashSessionClose
        method=="POST" && path=="/api/v1/sales/reservations" -> StaffPermission.SaleReserve
        method=="GET" && (path=="/api/v1/sales" || Regex("/api/v1/sales/operations/[^/]+").matches(path) || Regex("/api/v1/sales/[^/]+").matches(path)) -> StaffPermission.SaleRead
        method=="POST" && Regex("/api/v1/sales/[^/]+/commit").matches(path) -> StaffPermission.SaleCommit
        method=="POST" && Regex("/api/v1/sales/[^/]+/release").matches(path) -> StaffPermission.SaleRelease
        method=="POST" && path=="/api/v1/payments" -> StaffPermission.PaymentCapture
        method=="POST" && Regex("/api/v1/payments/[^/]+/reversals").matches(path) -> StaffPermission.PaymentReverse
        method=="POST" && path=="/api/v1/expenses" -> StaffPermission.ExpenseRecord
        method=="GET" && path=="/api/v2/reports/shift" -> StaffPermission.ShiftReportRead
        method=="GET" && path=="/api/v2/reports/day" -> StaffPermission.DailyReportRead
        method=="GET" && path=="/api/v1/reports/shift" -> StaffPermission.ShiftReportRead
        method=="GET" && path=="/api/v1/reports/daily" -> StaffPermission.DailyReportRead
        method=="POST" && path=="/api/v2/cash-sessions" -> StaffPermission.CashSessionOpen
        method=="POST" && Regex("/api/v2/cash-sessions/[^/]+/close").matches(path) -> StaffPermission.CashSessionClose
        method=="POST" && path=="/api/v2/expenses" -> StaffPermission.ExpenseRecord
        method=="POST" && path=="/api/v2/payments" -> StaffPermission.PaymentCapture
        method=="POST" && Regex("/api/v2/payments/[^/]+/reversals").matches(path) -> StaffPermission.PaymentReverse
        method=="GET" && Regex("/api/v2/accounting/commands/[^/]+").matches(path) -> StaffPermission.AccountingCommandRead
        else -> StaffPermission.Unknown
    }
    fun public(permission: StaffPermission)=permission in setOf(StaffPermission.PublicHealthRead,StaffPermission.CorsPreflight,StaffPermission.CsrfBootstrap,StaffPermission.StaffLogin)
}
class StaffSessionFilter(private val resolve: ResolveStaffSession,private val settings: StaffCookieSettings,private val mapper: ObjectMapper) : OncePerRequestFilter() {
    private val policy=StaffAuthorizationPolicy()
    override fun doFilterInternal(request: HttpServletRequest,response: HttpServletResponse,chain: FilterChain) {
        val permission=StaffHttpPermission.permission(request.method,request.requestURI)
        request.setAttribute("blackstore.staff-request-context",StaffRequestContext.Anonymous)
        response.setHeader("Cache-Control","no-store")
        try {
            if(!StaffHttpPermission.public(permission)) {
                val token=sessionCookie(request,settings.name)
                var resolved=resolve.execute(token)
                if(!policy.permits(resolved.staff.role,permission)) throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN)
                if(request.method !in setOf("GET","HEAD")) {
                    assertOrigin(request,settings)
                    if(!constantEquals(request.getHeader("X-CSRF-Token"),resolved.session.csrfToken)) throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
                    // Final atomic active-session touch is the mutation's admission point.
                    // A logout which won before this point denies; already admitted work may finish.
                    resolved=resolve.execute(token)
                    if(!policy.permits(resolved.staff.role,permission)) throw StaffSecurityException(StaffSecurityFailure.FORBIDDEN)
                    if(!constantEquals(request.getHeader("X-CSRF-Token"),resolved.session.csrfToken)) throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
                }
                request.setAttribute(SESSION_ATTRIBUTE,resolved)
                request.setAttribute("blackstore.staff-request-context",StaffRequestContext.Authenticated(resolved))
                SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(resolved.staff,null,emptyList())
            }
            chain.doFilter(request,response)
        } catch(e: StaffSecurityException) { securityError(response,e,request,mapper) }
        catch(e: java.sql.SQLException) { securityError(response,StaffSecurityException(StaffSecurityFailure.IDENTITY_UNAVAILABLE),request,mapper) }
        finally { SecurityContextHolder.clearContext() }
    }
    companion object { const val SESSION_ATTRIBUTE="blackstore.authenticated-session" }
}
fun HttpServletRequest.staffSession(): ResolvedStaffSession = getAttribute(StaffSessionFilter.SESSION_ATTRIBUTE) as? ResolvedStaffSession ?: throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
fun sessionCookie(request: HttpServletRequest,name: String): String? {
    val values=request.cookies?.filter { it.name==name } ?: emptyList()
    if(values.size>1) throw StaffSecurityException(StaffSecurityFailure.SESSION_INVALID)
    return values.singleOrNull()?.value
}
fun constantEquals(left: String?,right: String): Boolean = left != null && MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8),right.toByteArray(Charsets.UTF_8))
fun assertOrigin(request: HttpServletRequest,settings: StaffCookieSettings) {
    if(request.getHeader("Sec-Fetch-Site")=="cross-site") throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
    val supplied=request.getHeader("Origin") ?: return
    val port=request.serverPort
    val own="${request.scheme}://${request.serverName}" + if((request.scheme=="https" && port==443)||(request.scheme=="http" && port==80)) "" else ":$port"
    if(supplied != settings.allowedOrigin.takeIf { it.isNotBlank() } && supplied!=own) throw StaffSecurityException(StaffSecurityFailure.CSRF_INVALID)
}
fun securityError(response: HttpServletResponse,e: StaffSecurityException,request: HttpServletRequest,mapper: ObjectMapper) {
    response.status=e.status; response.contentType="application/json"; response.setHeader("Cache-Control","no-store")
    mapper.writeValue(response.outputStream,BaseResponse.error<Nothing>(HttpCode.entries.first { it.code==e.status },request.getHeader("X-Trace-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),e.errorCode,"Staff operation denied",false))
}
