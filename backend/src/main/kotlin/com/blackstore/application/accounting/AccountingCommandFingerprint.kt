package com.blackstore.application.accounting

import com.blackstore.domain.accounting.AccountingCommandDraft
import com.blackstore.domain.accounting.AccountingCommandKind
import com.blackstore.domain.accounting.ExpenseInstruction
import com.blackstore.domain.model.OperationQuadruple
import com.blackstore.domain.port.out.accounting.PaidFeeCommand
import com.blackstore.domain.sales.MoneyPolicy
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest

/** Versioned length-prefixed UTF-8 fields distinguish null, empty and delimiter-containing values. */
class AccountingCommandFingerprint {
    fun feeHash(actorId: Long, command: PaidFeeCommand): String = hash(actorId,
        AccountingCommandDraft.FeeRecord(command.commandId, command.cashSessionId, command.method,
            command.amount, command.reason, command.evidenceRef))

    fun hash(actorId: Long, command: AccountingCommandDraft): String {
        require(actorId > 0)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            val fields = CanonicalFields(output)
            fields.text("blackstore-accounting-command-v2")
            fields.text(actorId.toString())
            when (command) {
                is AccountingCommandDraft.CashSessionOpen -> {
                    fields.kind(AccountingCommandKind.CASH_SESSION_OPEN)
                    fields.text(command.terminalId.toString()); fields.text(command.cashierId.toString())
                    fields.money(command.openingCash); fields.text(command.reason)
                }
                is AccountingCommandDraft.CashSessionClose -> {
                    fields.kind(AccountingCommandKind.CASH_SESSION_CLOSE)
                    fields.text(command.cashSessionId.toString()); fields.money(command.declaredCash); fields.text(command.reason)
                }
                is AccountingCommandDraft.PaymentCapture -> {
                    fields.kind(AccountingCommandKind.PAYMENT_CAPTURE)
                    fields.identity(command.identity); fields.text(command.method.name); fields.money(command.amount); fields.text(command.reason)
                }
                is AccountingCommandDraft.PaymentReverse -> {
                    fields.kind(AccountingCommandKind.PAYMENT_REVERSE)
                    fields.identity(command.identity); fields.text(command.originalPaymentId.toString())
                    fields.text(command.reason); fields.text(command.evidenceRef)
                }
                is AccountingCommandDraft.FeeRecord -> {
                    fields.kind(AccountingCommandKind.FEE_RECORD)
                    fields.text(command.cashSessionId.toString()); fields.text(command.method.name); fields.money(command.amount)
                    fields.text(command.reason); fields.text(command.evidenceRef)
                }
                is AccountingCommandDraft.ExpenseRecord -> {
                    fields.kind(AccountingCommandKind.EXPENSE_RECORD)
                    fields.text(command.cashSessionId.toString()); fields.text(command.reason)
                    when (val instruction = command.instruction) {
                        is ExpenseInstruction.Accrue -> {
                            fields.text(ExpenseFingerprintKind.Accrue.name); fields.text(instruction.category); fields.money(instruction.amount)
                        }
                        is ExpenseInstruction.AccrueAndSettle -> {
                            fields.text(ExpenseFingerprintKind.AccrueAndSettle.name); fields.text(instruction.category)
                            fields.money(instruction.amount); fields.text(instruction.method.name)
                        }
                        is ExpenseInstruction.SettleExisting -> {
                            fields.text(ExpenseFingerprintKind.SettleExisting.name); fields.text(instruction.expenseId.toString()); fields.text(instruction.method.name)
                        }
                    }
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private enum class ExpenseFingerprintKind { Accrue, AccrueAndSettle, SettleExisting }

    private class CanonicalFields(private val output: DataOutputStream) {
        fun text(value: String?) {
            if (value == null) output.writeInt(-1)
            else value.toByteArray(Charsets.UTF_8).let { output.writeInt(it.size); output.write(it) }
        }
        fun kind(value: AccountingCommandKind) = text(value.name)
        fun money(value: BigDecimal) = text(MoneyPolicy.normalize(value).toPlainString())
        fun identity(value: OperationQuadruple) {
            text(value.clientInstanceId); text(value.deviceId); text(value.saleId); text(value.operationId)
        }
    }
}
