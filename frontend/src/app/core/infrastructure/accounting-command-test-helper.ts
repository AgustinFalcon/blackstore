import { AccountingCoverage, CommandOutcome } from '../domain/accounting-command';
import { ReconciliationOutcome } from '../domain/accounting-report';
export function commandEnvelope(commandId: string, overrides = {}) {
  return { code: 200, traceId: 'test', message: null, errorCode: null, retryable: null, data: {
    outcome: CommandOutcome.Committed.wire, commandId, cashSessionId: 2, ledgerEventIds: [1],
    committedAt: '2026-10-07T12:00:00Z', failure: null, paymentId: null, expenseId: null, settlementId: null,
    closeSnapshot: null, ...overrides,
  } };
}
export function closeFixture() {
  return { declaredCash: '90', expectedCash: '100', difference: '-10', outcome: ReconciliationOutcome.Shortage.wire,
    coverage: AccountingCoverage.Complete.wire };
}
