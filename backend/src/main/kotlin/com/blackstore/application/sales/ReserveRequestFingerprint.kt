package com.blackstore.application.sales

import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.storecore.ReserveLineCommand
import com.blackstore.domain.sales.MoneyPolicy
import com.blackstore.domain.sales.TicketLine
import java.security.MessageDigest

/** Fingerprints admission evidence, independently of catalog-normalized dispatch. */
object ReserveRequestFingerprint {
    fun hash(identity: OperationQuadruple, cashSessionId: Long, lines: List<ReserveLineCommand>, ticket: List<TicketLine>, reason: String?): String {
        val fields = buildList {
            add("RESERVE_REQUEST_V1")
            add(identity.clientInstanceId); add(identity.deviceId); add(identity.saleId); add(identity.operationId)
            add(cashSessionId.toString()); add(reason ?: ""); add(lines.size.toString())
            lines.forEach { add(it.variantId); add(it.quantity.toString()); add(it.expectedPriceVersion) }
            add(ticket.size.toString())
            ticket.forEach {
                add(it.sku); add(it.productName); add(it.quantity.toString())
                add(MoneyPolicy.normalize(it.originalUnitPrice).toPlainString())
                add(MoneyPolicy.normalize(it.discountAmount).toPlainString())
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8)); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
