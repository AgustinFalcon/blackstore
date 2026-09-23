package com.blackstore.domain.compliance

import com.blackstore.domain.companion.CompanionEnvironment
import com.blackstore.domain.exception.ForbiddenOperationException
import com.blackstore.domain.sales.FiscalStatus
import java.time.Instant

enum class FiscalAuthorizationKind {
    EXTERNAL_MECHANISM_VALID,
    LAWFUL_EXCEPTION,
}

data class FiscalAuthorization(
    val kind: FiscalAuthorizationKind,
    val responsibleApprovalRef: String,
    val accountantApprovalRef: String,
    val validFrom: Instant,
    val validUntil: Instant,
) {
    init {
        require(responsibleApprovalRef.isNotBlank() && accountantApprovalRef.isNotBlank())
        require(validUntil.isAfter(validFrom))
    }

    fun isValidAt(now: Instant): Boolean = !now.isBefore(validFrom) && validUntil.isAfter(now)
}

class FiscalBoundaryPolicy {

    fun assertCanCommit(
        environment: CompanionEnvironment,
        fiscalStatus: FiscalStatus,
        authorization: FiscalAuthorization?,
        now: Instant,
    ) {
        if (environment == CompanionEnvironment.PRODUCTION && fiscalStatus == FiscalStatus.NOT_CONFIGURED) {
            throw ForbiddenOperationException("NOT_CONFIGURED is test and development only")
        }
        if (environment == CompanionEnvironment.PRODUCTION && authorization?.isValidAt(now) != true) {
            throw ForbiddenOperationException("production COMMITTED requires a valid external fiscal mechanism or lawful exception")
        }
    }
}
