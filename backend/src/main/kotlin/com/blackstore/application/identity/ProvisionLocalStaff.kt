package com.blackstore.application.identity

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*

enum class StaffProvisionOperation { CREATE, RESET, UNKNOWN }
data class StaffProvisionRequest(val operation: StaffProvisionOperation, val identifier: String, val displayName: String, val role: StaffRole, val localActor: String)
interface LocalStaffProvisionPort { fun execute(request: StaffProvisionRequest, password: CharArray): StaffUserId }
class ProvisionLocalStaff(private val port: LocalStaffProvisionPort) {
    fun execute(request: StaffProvisionRequest, confirmation: String, password: CharArray): StaffUserId {
        try {
            require(request.operation != StaffProvisionOperation.UNKNOWN && request.role != StaffRole.UNKNOWN)
            require(confirmation == request.identifier) { "target confirmation does not match" }
            require(request.identifier.length in 1..160 && request.displayName.length in 1..160)
            require(password.size in 12..72) { "staff password must contain 12 to 72 characters" }
            return port.execute(request,password)
        } finally { password.fill('\u0000') }
    }
}
