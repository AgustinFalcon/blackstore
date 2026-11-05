import { AccountingFormula, AccountingMetric, DataCompleteness, FormulaVersion, ReconciliationOutcome } from '../domain/accounting-report';
import { PaymentMethod, ReportPeriod } from '../domain/pos-types';

/** Contract fixture, not backend/PG evidence. Wire cases are owned by the closed types. */
export function reportFixture(period = ReportPeriod.Shift) {
  const complete = () => ({ state: DataCompleteness.Complete.wire, causes: [] as string[] });
  return {
    periodKind: period.wire, cashSessionId: period === ReportPeriod.Shift ? 12 : null,
    localDate: period === ReportPeriod.Day ? '2026-10-07' : null,
    bounds: { start: '2026-10-07T03:00:00Z', endExclusive: '2026-10-08T03:00:00Z' },
    cutoff: '2026-10-08T04:00:00Z', snapshot: '10:20:', zone: 'America/Argentina/Buenos_Aires', zoneVersion: 'zone-v1',
    zoneEffectiveAt: '2026-01-01T00:00:00Z', accountingVersion: 1, provisional: false,
    grossSales: 100, discounts: 10, netSales: 90,
    totalsByMethod: Object.fromEntries(PaymentMethod.selectable.map(method => [method.wire,
      { collected: 100, refunds: 20, feesPaid: 5, expensesPaid: 10, operatingCashFlow: 65 }])),
    completeness: Object.fromEntries(AccountingMetric.all.map(metric => [metric.wire, complete()])),
    formula: { kind: AccountingFormula.Contribution.wire, version: FormulaVersion.V1.wire, value: 75, completeness: complete() },
    reconciliation: period === ReportPeriod.Shift ? {
      expectedCash: 65 as number | null, declaredCash: 65 as number | null, difference: 0 as number | null,
      outcome: ReconciliationOutcome.Balanced.wire, localWatermark: 15,
    } : null,
  };
}
export function reportEnvelope(data: unknown) { return { code: 200, traceId: 'test', data, message: null, errorCode: null, retryable: null }; }
