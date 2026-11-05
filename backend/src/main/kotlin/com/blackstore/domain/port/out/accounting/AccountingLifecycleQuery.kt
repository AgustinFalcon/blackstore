package com.blackstore.domain.port.out.accounting

import com.blackstore.domain.accounting.*
import com.blackstore.domain.identity.AuthenticatedStaff
import java.time.Instant

data class AccountingLifecycleObservation(val state: AccountingRuntimeState,val activationAt: Instant?,val contractVersion: AccountingContractVersion,val observedAt: Instant) {
    init { require(state!=AccountingRuntimeState.Unknown && contractVersion==AccountingContractVersion.V2);require((state==AccountingRuntimeState.PreActivation)==(activationAt==null)) }
}
sealed interface AccountingLifecycleResult {
    data class Observed(val observation: AccountingLifecycleObservation): AccountingLifecycleResult
    data object Unavailable: AccountingLifecycleResult
    data object Unknown: AccountingLifecycleResult
}
interface AccountingLifecycleQuery { fun observe(staff: AuthenticatedStaff): AccountingLifecycleResult }
