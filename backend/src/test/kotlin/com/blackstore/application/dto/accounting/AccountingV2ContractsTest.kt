package com.blackstore.application.dto.accounting

import com.blackstore.domain.accounting.AccountingCommandDraft
import com.blackstore.domain.accounting.AccountingCommandKind
import com.blackstore.domain.accounting.AccountingCommandReceipt
import com.blackstore.domain.accounting.AccountingCommandResult
import com.blackstore.domain.sales.PaymentMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AccountingV2ContractsTest {
    private val commandId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `request translator maps the closed v2 vocabulary once`() {
        val expense = AccountingV2RequestTranslator.translate(
            ExpenseRecordV2Request(commandId, 7, "OPERATING", BigDecimal("12.50"), "supplies", "CASH"),
        ) as AccountingCommandDraft.ExpenseRecord
        assertEquals(PaymentMethod.CASH, expense.method)
        assertEquals(BigDecimal("12.50"), expense.amount)

        val capture = AccountingV2RequestTranslator.translate(
            PaymentCaptureV2Request(commandId, "client", "device", "sale", "operation", "CARD", BigDecimal("10.00")),
        ) as AccountingCommandDraft.PaymentCapture
        assertEquals(PaymentMethod.CARD, capture.method)
        assertThrows(IllegalArgumentException::class.java) {
            AccountingV2RequestTranslator.translate(
                PaymentCaptureV2Request(commandId, "client", "device", "sale", "operation", "CRYPTO", BigDecimal("10.00")),
            )
        }
    }

    @Test
    fun `receipt response never leaks raw or unfamiliar outcomes`() {
        val receipt = AccountingCommandReceipt(commandId, 1, AccountingCommandKind.PAYMENT_CAPTURE, 2, "a".repeat(64), listOf(3), Instant.EPOCH)
        assertEquals(AccountingCommandOutcomeV2.Committed, AccountingV2ResponseTranslator.translate(AccountingCommandResult.Committed(receipt)).outcome)
        assertEquals(AccountingCommandOutcomeV2.NotFound, AccountingV2ResponseTranslator.translate(AccountingCommandResult.NotFound).outcome)
        assertEquals(AccountingCommandOutcomeV2.Unknown, AccountingCommandOutcomeV2.fromWire("unexpected"))
    }
}
