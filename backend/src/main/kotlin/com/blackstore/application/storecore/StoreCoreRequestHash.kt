package com.blackstore.application.storecore

import com.blackstore.domain.model.StoreCoreOperationKind
import java.security.MessageDigest

object StoreCoreRequestHash {
    fun hex(kind: StoreCoreOperationKind, operationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${kind.name}:$operationId".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
