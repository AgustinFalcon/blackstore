package com.blackstore.application.accounting

import com.blackstore.domain.accounting.*
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.accounting.PaidFeeCommand
import com.blackstore.domain.sales.PaymentMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class AccountingCommandFingerprintTest {
    private val fingerprint = AccountingCommandFingerprint()
    private val id = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val identity = OperationQuadruple("client", "device", "sale", "operation")
    private val capture = AccountingCommandDraft.PaymentCapture(id, identity, PaymentMethod.CASH, BigDecimal.ONE)

    @Test fun `money normalization and command key preserve same semantic fingerprint`() {
        val original = fingerprint.hash(1, capture)
        assertEquals(64, original.length)
        assertTrue(original.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(original, fingerprint.hash(1, capture.copy(amount = BigDecimal("1.000"))))
        assertEquals(original, fingerprint.hash(1, capture.copy(commandId = UUID.randomUUID())))
        assertNotEquals(original, fingerprint.hash(2, capture))
        assertThrows(IllegalArgumentException::class.java) { fingerprint.hash(0, capture) }
    }

    @Test fun `each capture field participates and Unicode fields cannot collide by delimiters`() {
        val original = fingerprint.hash(1, capture)
        listOf(
            capture.copy(identity = identity.copy(clientInstanceId = "different")),
            capture.copy(identity = identity.copy(deviceId = "different")),
            capture.copy(identity = identity.copy(saleId = "different")),
            capture.copy(identity = identity.copy(operationId = "different")),
            capture.copy(method = PaymentMethod.CARD), capture.copy(amount = BigDecimal.TEN),
            capture.copy(reason = "authorized override"),
        ).forEach { assertNotEquals(original, fingerprint.hash(1, it)) }
        val first = capture.copy(identity = identity.copy(deviceId = "a|β", saleId = "c"))
        val second = capture.copy(identity = identity.copy(deviceId = "a", saleId = "β|c"))
        assertNotEquals(fingerprint.hash(1, first), fingerprint.hash(1, second))
        val open = AccountingCommandDraft.CashSessionOpen(id, 1, 1, BigDecimal.ZERO, null)
        assertNotEquals(fingerprint.hash(1, open), fingerprint.hash(1, open.copy(reason = "")))
    }

    @Test fun `expense instructions fees reversals and aggregate references have distinct payloads`() {
        val accrue = AccountingCommandDraft.ExpenseRecord(id, 1, "stock purchase", ExpenseInstruction.Accrue("supplies", BigDecimal.TEN))
        val immediate = accrue.copy(instruction = ExpenseInstruction.AccrueAndSettle("supplies", BigDecimal.TEN, PaymentMethod.CASH))
        val settlement = accrue.copy(instruction = ExpenseInstruction.SettleExisting(1, PaymentMethod.CASH))
        val fee = AccountingCommandDraft.FeeRecord(id, 1, PaymentMethod.CASH, BigDecimal.TEN, "provider paid", "receipt")
        val reverse = AccountingCommandDraft.PaymentReverse(id, identity, 1, "cancelled", "receipt")
        val close = AccountingCommandDraft.CashSessionClose(id, 1, BigDecimal.TEN, "counted")
        val open = AccountingCommandDraft.CashSessionOpen(id, 1, 1, BigDecimal.TEN, "opening")
        val commands = listOf(accrue, immediate, settlement, fee, reverse, close, open, capture)
        assertEquals(commands.size, commands.map { fingerprint.hash(1, it) }.distinct().size)
        listOf(fee.copy(cashSessionId = 2), fee.copy(method = PaymentMethod.CARD), fee.copy(amount = BigDecimal.ONE), fee.copy(reason = "different"), fee.copy(evidenceRef = "different"))
            .forEach { assertNotEquals(fingerprint.hash(1, fee), fingerprint.hash(1, it)) }
        listOf(reverse.copy(identity = identity.copy(saleId = "other")), reverse.copy(originalPaymentId = 2), reverse.copy(reason = "other"), reverse.copy(evidenceRef = "other"))
            .forEach { assertNotEquals(fingerprint.hash(1, reverse), fingerprint.hash(1, it)) }
        assertNotEquals(fingerprint.hash(1, settlement), fingerprint.hash(1, settlement.copy(instruction = ExpenseInstruction.SettleExisting(2, PaymentMethod.CASH))))
        assertNotEquals(fingerprint.hash(1, settlement), fingerprint.hash(1, settlement.copy(instruction = ExpenseInstruction.SettleExisting(1, PaymentMethod.CARD))))
        listOf(accrue.copy(cashSessionId = 2), accrue.copy(reason = "other"),
            accrue.copy(instruction = ExpenseInstruction.Accrue("other", BigDecimal.TEN)),
            accrue.copy(instruction = ExpenseInstruction.Accrue("supplies", BigDecimal.ONE)))
            .forEach { assertNotEquals(fingerprint.hash(1, accrue), fingerprint.hash(1, it)) }
        listOf(open.copy(terminalId = 2), open.copy(cashierId = 2), open.copy(openingCash = BigDecimal.ONE), open.copy(reason = "other"))
            .forEach { assertNotEquals(fingerprint.hash(1, open), fingerprint.hash(1, it)) }
        listOf(close.copy(cashSessionId = 2), close.copy(declaredCash = BigDecimal.ONE), close.copy(reason = "other"))
            .forEach { assertNotEquals(fingerprint.hash(1, close), fingerprint.hash(1, it)) }
    }

    @Test fun `fee draft rejects unknown method money or missing evidence`() {
        val fee = AccountingCommandDraft.FeeRecord(id, 1, PaymentMethod.CASH, BigDecimal.ONE, "paid fee", "proof")
        val operational = PaidFeeCommand(fee.commandId, fee.cashSessionId, fee.method, fee.amount, fee.reason, fee.evidenceRef)
        assertEquals(fingerprint.hash(1, fee), fingerprint.feeHash(1, operational))
        assertThrows(IllegalArgumentException::class.java) { fee.copy(method = PaymentMethod.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) { fee.copy(amount = BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { fee.copy(amount = BigDecimal("0.001")) }
        assertThrows(IllegalArgumentException::class.java) { fee.copy(reason = "") }
        assertThrows(IllegalArgumentException::class.java) { fee.copy(evidenceRef = "") }
        assertThrows(IllegalArgumentException::class.java) { capture.copy(reason = " ") }
    }

    @Test fun `receipt references remain typed and positive without breaking old construction`() {
        val receipt = AccountingCommandReceipt(id, 1, AccountingCommandKind.PAYMENT_CAPTURE, 1,
            fingerprint.hash(1, capture), listOf(1), java.time.Instant.EPOCH)
        assertNull(receipt.paymentId)
        assertEquals(2L, receipt.copy(saleId = 1, paymentId = 2).paymentId)
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(paymentId = 0) }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(ledgerEventIds = listOf(1, 1)) }
    }
}
