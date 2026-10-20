package com.blackstore.domain.accounting

import java.time.Instant

enum class AccountingRuntimeState(val wire: String) {
    PreActivation("PRE_ACTIVATION"), Active("ACTIVE"), Paused("PAUSED"), Unknown("UNKNOWN");
    companion object { fun fromWire(value: String?): AccountingRuntimeState = entries.firstOrNull { it.wire == value } ?: Unknown }
}

enum class AccountingContractVersion { V1, V2, Unknown;
    companion object { fun fromWire(value: String?): AccountingContractVersion = entries.firstOrNull { it.name == value } ?: Unknown }
}

enum class AccountingMutationAdmission { Allowed, AccountingNotActivated, LegacyContractDisabled, AccountingPaused, Unknown }

/** The activation instant is permanent, including while operational writes are paused. */
data class AccountingLifecycle(val state: AccountingRuntimeState, val activatedAt: Instant?) {
    init {
        require(state != AccountingRuntimeState.Unknown)
        require((state == AccountingRuntimeState.PreActivation) == (activatedAt == null))
    }

    fun activate(at: Instant, readiness: AccountingActivationReadiness): AccountingLifecycle {
        require(state == AccountingRuntimeState.PreActivation && readiness.ready)
        return AccountingLifecycle(AccountingRuntimeState.Active, at)
    }

    fun pause(): AccountingLifecycle {
        require(state == AccountingRuntimeState.Active)
        return copy(state = AccountingRuntimeState.Paused)
    }

    fun resume(): AccountingLifecycle {
        require(state == AccountingRuntimeState.Paused)
        return copy(state = AccountingRuntimeState.Active)
    }
}

data class AccountingActivationReadiness(
    val migrationVerified: Boolean,
    val clientsV2Verified: Boolean,
    val writersAccepted: Boolean,
    val compatibleBinaryVerified: Boolean,
    val designApproved: Boolean,
) {
    val ready: Boolean get() = migrationVerified && clientsV2Verified && writersAccepted && compatibleBinaryVerified && designApproved
}

class AccountingRuntimePolicy {
    fun admit(state: AccountingRuntimeState, version: AccountingContractVersion): AccountingMutationAdmission = when {
        state == AccountingRuntimeState.Unknown || version == AccountingContractVersion.Unknown -> AccountingMutationAdmission.Unknown
        state == AccountingRuntimeState.Paused && version == AccountingContractVersion.V1 -> AccountingMutationAdmission.LegacyContractDisabled
        state == AccountingRuntimeState.Paused -> AccountingMutationAdmission.AccountingPaused
        state == AccountingRuntimeState.PreActivation && version == AccountingContractVersion.V1 -> AccountingMutationAdmission.Allowed
        state == AccountingRuntimeState.PreActivation -> AccountingMutationAdmission.AccountingNotActivated
        version == AccountingContractVersion.V1 -> AccountingMutationAdmission.LegacyContractDisabled
        else -> AccountingMutationAdmission.Allowed
    }
}
