package com.blackstore.application.sales

import com.blackstore.domain.sales.*
import java.security.MessageDigest

/** Length-prefixed fields prevent ambiguous concatenation. Only normalized semantic values enter v1. */
class SaleCommandFingerprint {
    fun hash(actorId: Long, cashId: Long, command: SaleCommand): String {
        val q = command.identity
        val fields = mutableListOf("sale-admission-v1",command.kind.name,actorId.toString(),cashId.toString(),q.clientInstanceId,q.deviceId,q.saleId,q.operationId,command.reason.orEmpty())
        if (command is SaleCommand.Reserve) fields += listOf(command.cashSessionId.toString(),command.variantId,command.quantity.toString(),command.expectedPriceVersion,
            command.line.sku,command.line.productName,MoneyPolicy.normalize(command.line.originalUnitPrice).toPlainString(),MoneyPolicy.normalize(command.line.discountAmount).toPlainString())
        val encoded = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
