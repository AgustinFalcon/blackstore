package com.blackstore.application.storecore

import com.blackstore.domain.model.StoreCoreCanonicalContract
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Fail closed if configured path/version/digest drift from the pin.
 * Does not parse the StoreCore YAML; headers, scopes, and x-canonical stay unpinned until a later task.
 */
@Component
class StoreCoreContractCompatibilityGuard(
    @Value("\${blackstore.storecore.contract.canonical-path}") path: String,
    @Value("\${blackstore.storecore.contract.version}") version: String,
    @Value("\${blackstore.storecore.contract.sha256}") digest: String,
) {
    init {
        StoreCoreCanonicalContract.assertCompatible(path, version, digest)
    }
}
