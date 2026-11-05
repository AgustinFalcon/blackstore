import { AccountingFormula, AccountingMetric, AccountingReportRequest, CompletenessCause, DataCompleteness, FormulaVersion, ReconciliationOutcome, ReportFailure } from '../domain/accounting-report';
import { PaymentMethod, ReportPeriod } from '../domain/pos-types';
import { PosWireMapper } from './pos-wire-mapper';
import { reportEnvelope, reportFixture } from './accounting-report-test-helper';

describe('Accounting report closed domain and wire mapper', () => {
  const shift = AccountingReportRequest.shift(12)!;
  it('translates every finite case and unknown values neutrally', () => {
    for (const type of [DataCompleteness, CompletenessCause, AccountingFormula, FormulaVersion, ReconciliationOutcome, AccountingMetric]) {
      for (const value of Object.values(type)) {
        if (value && typeof value === 'object' && 'wire' in value) expect(type.fromWire(value.wire)).toBe(value);
      }
      expect(type.fromWire('FUTURE_CASE')).toBe(type.Unknown);
    }
    for (const value of [ReportFailure.Validation, ReportFailure.Unauthenticated, ReportFailure.Forbidden,
      ReportFailure.NotVisible, ReportFailure.Conflict, ReportFailure.Unavailable]) expect(PosWireMapper.reportFailure(value.status)).toBe(value);
    expect(PosWireMapper.reportFailure('FUTURE_CASE')).toBe(ReportFailure.Unknown);
  });
  it('validates periods, dates, IANA zones and filters before issuing a query', () => {
    expect(AccountingReportRequest.shift(0)).toBeNull();
    expect(AccountingReportRequest.shift('12junk')).toBeNull();
    expect(AccountingReportRequest.day('2026-02-30', 'America/Argentina/Buenos_Aires')).toBeNull();
    expect(AccountingReportRequest.day('2026-10-07', 'invented/zone')).toBeNull();
    expect(AccountingReportRequest.day('2026-10-07', 'America/Argentina/Buenos_Aires', { cashierId: -1 })).toBeNull();
    const day = AccountingReportRequest.day('2026-10-07', 'America/Argentina/Buenos_Aires', { cashierId: 7, terminalId: 2, cashSessionId: 12 })!;
    expect(day.period).toBe(ReportPeriod.Day);
    expect(day.params['cashierId']).toBe('7');
    expect(Object.keys(day.params)).toEqual(['localDate', 'zone', 'cashierId', 'terminalId', 'cashSessionId']);
  });
  it('keeps exact money, snapshot and separate financial totals', () => {
    const report = PosWireMapper.accountingReport(reportEnvelope(reportFixture()), shift)!;
    expect(report.netSales.value?.decimal).toBe('90.00');
    expect(report.totalsByMethod.find(row => row.method === PaymentMethod.Card)?.operatingCashFlow?.decimal).toBe('65.00');
    expect(report.formula.kind).toBe(AccountingFormula.Contribution);
    expect(report.snapshot).toBe('10:20:');
  });
  it('rejects a malformed envelope and another cash session/day', () => {
    const raw = reportFixture(); raw.cashSessionId = 99;
    expect(PosWireMapper.accountingReport(reportEnvelope(raw), shift)).toBeNull();
    expect(PosWireMapper.accountingReport({ ...reportEnvelope(reportFixture()), code: 503 }, shift)).toBeNull();
    const day = reportFixture(ReportPeriod.Day); day.localDate = '2026-10-06';
    expect(PosWireMapper.accountingReport(reportEnvelope(day), AccountingReportRequest.day('2026-10-07', day.zone)!)).toBeNull();
  });
  it('hides official amounts when legacy coverage has no evidence and preserves declaration', () => {
    const raw = reportFixture();
    raw.completeness[AccountingMetric.NetSales.wire] = { state: DataCompleteness.LegacyIncomplete.wire, causes: [CompletenessCause.LegacyActivity.wire] };
    raw.reconciliation!.expectedCash = null; raw.reconciliation!.difference = null;
    raw.reconciliation!.outcome = ReconciliationOutcome.Unavailable.wire;
    const report = PosWireMapper.accountingReport(reportEnvelope(raw), shift)!;
    expect(report.netSales.value).toBeNull(); expect(report.grossSales).toBeNull();
    expect(report.netSales.coverage.state).toBe(DataCompleteness.LegacyIncomplete);
    expect(report.reconciliation!.declaredCash?.decimal).toBe('65.00');
    expect(report.reconciliation!.expectedCash).toBeNull(); expect(report.reconciliation!.difference).toBeNull();
  });
  it('does not round invalid money or manufacture missing method zeros', () => {
    const raw = reportFixture(); raw.netSales = 1.001;
    delete raw.totalsByMethod[PaymentMethod.Transfer.wire];
    const report = PosWireMapper.accountingReport(reportEnvelope(raw), shift)!;
    expect(report.netSales.value).toBeNull(); expect(report.netSales.coverage.state).toBe(DataCompleteness.Unknown);
    expect(report.totalsByMethod.find(row => row.method === PaymentMethod.Transfer)!.values.every(row => row.value === null)).toBeTrue();
    expect(report.formula.value).toBeNull(); expect(report.formula.coverage.state).toBe(DataCompleteness.Unknown);
    expect(report.coverage.find(row => row.metric === AccountingMetric.Contribution)!.coverage.state).toBe(DataCompleteness.Unknown);
  });
  it('invalidates contribution when a required financial input loses evidence', () => {
    const raw = reportFixture();
    Reflect.deleteProperty(raw.totalsByMethod[PaymentMethod.Cash.wire], 'feesPaid');
    const report = PosWireMapper.accountingReport(reportEnvelope(raw), shift)!;
    expect(report.coverage.find(row => row.metric === AccountingMetric.FeesPaid)!.coverage.state).toBe(DataCompleteness.Unknown);
    expect(report.formula.value).toBeNull(); expect(report.formula.coverage.state).toBe(DataCompleteness.Unknown);
    expect(report.coverage.find(row => row.metric === AccountingMetric.Contribution)!.coverage.state).toBe(DataCompleteness.Unknown);
  });
  it('maps unknown source/method/outcome without exposing the wire value', () => {
    const raw = reportFixture();
    raw.completeness[AccountingMetric.Collected.wire] = { state: DataCompleteness.Complete.wire, causes: ['FUTURE_CAUSE'] };
    raw.totalsByMethod['FUTURE_METHOD'] = raw.totalsByMethod[PaymentMethod.Cash.wire];
    raw.reconciliation!.outcome = 'FUTURE_OUTCOME';
    const report = PosWireMapper.accountingReport(reportEnvelope(raw), shift)!;
    expect(report.totalsByMethod.find(row => row.method === PaymentMethod.Unknown)!.values.every(row => row.value === null)).toBeTrue();
    expect(report.coverage.find(row => row.metric === AccountingMetric.Collected)!.coverage.state).toBe(DataCompleteness.Unknown);
    expect(report.reconciliation!.outcome).toBe(ReconciliationOutcome.Unknown);
    expect(report.reconciliation!.difference).toBeNull();
  });
  it('verifies signs and exact reconciliation arithmetic for every available outcome', () => {
    for (const [outcome, declared, difference] of [[ReconciliationOutcome.Balanced, 65, 0], [ReconciliationOutcome.Shortage, 60, -5], [ReconciliationOutcome.Overage, 70, 5]] as const) {
      const raw = reportFixture(); Object.assign(raw.reconciliation!, { outcome: outcome.wire, declaredCash: declared, difference });
      expect(PosWireMapper.accountingReport(reportEnvelope(raw), shift)!.reconciliation!.outcome).toBe(outcome);
    }
    const raw = reportFixture(); raw.reconciliation!.difference = 5;
    expect(PosWireMapper.accountingReport(reportEnvelope(raw), shift)!.reconciliation!.outcome).toBe(ReconciliationOutcome.Unknown);
  });
  it('keeps unapproved formulas unavailable and unknown versions neutral', () => {
    const raw = reportFixture(); raw.formula.kind = AccountingFormula.ReinvestmentSuggestion.wire;
    raw.formula.completeness = { state: DataCompleteness.Unavailable.wire, causes: [CompletenessCause.FormulaNotApproved.wire] };
    expect(PosWireMapper.accountingReport(reportEnvelope(raw), shift)!.formula.value).toBeNull();
    raw.formula.version = 'future-version';
    expect(PosWireMapper.accountingReport(reportEnvelope(raw), shift)!.formula.version).toBe(FormulaVersion.Unknown);
  });
});
