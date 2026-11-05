package com.blackstore.application.sales

import com.blackstore.domain.sales.*
import java.security.MessageDigest

/** Separate codec: historical AdmissionV1 is never reinterpreted. */
class SaleAdmissionV2Fingerprint {
    fun canonical(actorId: Long, cashId: Long, command: SaleCommand.ReserveMultiline): String {
        require(actorId > 0 && cashId == command.cashSessionId)
        val q = command.identity
        val fields = mutableListOf(SaleFingerprintVersion.AdmissionV2.wire, command.kind.name,
            actorId.toString(), cashId.toString(), q.clientInstanceId, q.deviceId, q.saleId, q.operationId, command.reason.orEmpty(),
            command.lines.size.toString())
        command.lines.sortedWith { a, b -> compareUtf8(a.variantId, b.variantId) }.forEach { line ->
            fields += listOf(line.variantId, line.quantity.toString(), line.expectedPriceVersion,
                line.ticketLine.sku, line.ticketLine.productName,
                MoneyPolicy.normalize(line.ticketLine.originalUnitPrice).toPlainString(),
                MoneyPolicy.normalize(line.ticketLine.discountAmount).toPlainString())
        }
        return fields.joinToString("") {
            SaleTextV2.validate(it)
            "${it.toByteArray(Charsets.UTF_8).size}:$it"
        }
    }
    fun hash(actorId: Long, cashId: Long, command: SaleCommand.ReserveMultiline): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(actorId, cashId, command).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun compareUtf8(left: String, right: String): Int {
        val a = SaleTextV2.validate(left).toByteArray(Charsets.UTF_8)
        val b = SaleTextV2.validate(right).toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(a.size, b.size)) {
            val difference = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (difference != 0) return difference
        }
        return a.size.compareTo(b.size)
    }
}
