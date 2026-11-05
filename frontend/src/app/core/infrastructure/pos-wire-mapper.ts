import {
  CashSessionStatus,
  CashMutationOutcome,
  PaymentStatus,
  PersistenceMode,
  SaleStatus,
} from '../domain/pos-types';
import {
  CashSessionData,
  CashSessionWire,
  ShiftReportData,
  ShiftReportWire,
  WorkspaceData,
  WorkspaceWire,
} from '../models/pos-models';
import { ReportFormula, ReportPeriod } from '../domain/pos-types';
import { BaseResponse } from '../models/base-response';
import { CapturedPayment, PaymentAttempt, PaymentCoverage, PaymentLedgerSemantics, TicketIdentity, TicketMoney, TicketSnapshot, sameTicketIdentity } from '../domain/ticket-transition';
import { PaymentMethod } from '../domain/pos-types';
import { AllowedAction, DurableCommandKind, DurableSaleDetail, DurableSalePage, DurableSaleState, DurableSaleSummary, PaymentReversibility } from '../domain/durable-sale';
import { AccountingFormula, AccountingMetric, AccountingReport, AccountingReportRequest, CompletenessCause, CoveredValue, DataCompleteness, FormulaVersion, MetricCoverage, Reconciliation, ReconciliationOutcome, ReportFailure } from '../domain/accounting-report';

import { AccountingCommand, AccountingCommandKind, AccountingCoverage, CommandFailure, CommandOutcome, CommandReceipt } from '../domain/accounting-command';
import { PosContextObservation, PosContextState } from '../domain/pos-execution-context';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { ExpenseOperation } from '../domain/accounting-command';
import { ExpenseProjectionObservation, ExpenseProjectionState, ExpensePostingKind, ExpenseFact, ExpenseSettlement, ExpensePosting } from '../domain/expense-projection';
import { SaleAdmission, SaleAdmissionOutcome, SaleCommand, SaleCommandKind,SaleAdmissionVerification } from '../domain/sale-command';

export class PosWireMapper {
  private constructor() {}
  static expenseProjection(response:unknown,httpStatus:number):ExpenseProjectionObservation {
    const unknown=Object.freeze({state:ExpenseProjectionState.Unknown,projection:null});
    const envelope=this.record(response),data=this.record(envelope?.['data']);
    if(!envelope || !this.nonempty(envelope['traceId']) || !data)return unknown;
    const state=ExpenseProjectionState.fromWire(data['state']);
    if(state!==ExpenseProjectionState.Found){
      const code=state===ExpenseProjectionState.NotFound?404:state===ExpenseProjectionState.Unavailable?503:null;
      return code===httpStatus && code===envelope['code'] && data['projection']===null ? {state,projection:null}:unknown;
    }
    const p=this.record(data['projection']),e=this.record(p?.['expense']),a=this.record(p?.['accountingEvidence']),snap=this.record(a?.['snapshot']);
    const instant=(v:unknown):v is string=>typeof v==='string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(v) && Number.isFinite(Date.parse(v));
    const ids=(v:unknown):v is number[]=>Array.isArray(v) && v.every(id=>this.positiveId(id)) && new Set(v).size===v.length;
    const amount=TicketMoney.fromDecimal(e?.['amount']);
    const operation=ExpenseOperation.fromWire(p?.['operation']);
    if(httpStatus!==200 || envelope['code']!==200 || envelope['errorCode']!==null || envelope['error']!=null ||
      !p || p['kind']!=='EXPENSE_RECORD' || !this.uuid(p['commandId']) ||
      operation===ExpenseOperation.Unknown || !this.positiveId(p['cashSessionId']) || !this.positiveId(p['expenseId']) ||
      (p['settlementId']!==null && !this.positiveId(p['settlementId'])) || !ids(p['ledgerEventIds']) || !e ||
      !this.positiveId(e['id']) || !this.positiveId(e['cashSessionId']) || !this.positiveId(e['actorId']) ||
      !this.nonempty(e['category']) || !amount || amount.cents<=0n || !instant(e['createdAt']) || !a ||
      a['accountingVersion']!==2 || DataCompleteness.fromWire(a['completeness'])!==DataCompleteness.Complete ||
      !Array.isArray(a['causes']) || a['causes'].length!==0 || !instant(a['committedAt']) || !snap ||
      !this.nonempty(snap['id']) || !instant(snap['cutoff']) || !instant(snap['asOf']) ||
      Date.parse(a['committedAt'])>Date.parse(snap['cutoff']) || Date.parse(snap['cutoff'])>Date.parse(snap['asOf']) ||
      !Array.isArray(a['postings']))return unknown;
    const expenseMethod=e['paymentMethod']===null?null:PaymentMethod.fromWire(e['paymentMethod']);
    if(expenseMethod===PaymentMethod.Unknown)return unknown;
    const expense:ExpenseFact=Object.freeze({id:e['id'] as number,cashSessionId:e['cashSessionId'] as number,actorId:e['actorId'] as number,
      category:e['category'] as string,amount,createdAt:e['createdAt'],paymentMethod:expenseMethod});
    let settlement:ExpenseSettlement|null=null;
    if(p['settlement']!==null){
      const s=this.record(p['settlement']),money=TicketMoney.fromDecimal(s?.['amount']),method=PaymentMethod.fromWire(s?.['paymentMethod']);
      if(!s || !['id','expenseId','cashSessionId','actorId'].every(k=>this.positiveId(s[k])) || !this.uuid(s['commandId']) ||
        !money || money.cents<=0n || method===PaymentMethod.Unknown || !instant(s['paidAt']))return unknown;
      settlement=Object.freeze({id:s['id'] as number,expenseId:s['expenseId'] as number,cashSessionId:s['cashSessionId'] as number,
        actorId:s['actorId'] as number,commandId:s['commandId'],amount:money,paymentMethod:method,paidAt:s['paidAt']});
    }
    const postings:ExpensePosting[]=[];
    for(const raw of a['postings']){
      const v=this.record(raw),origin=this.record(v?.['origin']),money=TicketMoney.fromDecimal(v?.['amount']);
      const kind=ExpensePostingKind.fromWire(v?.['kind'],v?.['component']),method=PaymentMethod.fromWire(v?.['paymentMethod']);
      if(!v || !['id','cashSessionId','expenseId','actorId'].every(k=>this.positiveId(v[k])) || !this.uuid(v['commandId']) ||
        !instant(v['occurredAt']) || v['accountingVersion']!==2 || kind===ExpensePostingKind.Unknown || method===PaymentMethod.Unknown ||
        !money || !origin || origin['kind']!=='EXPENSE' || origin['id']!==String(p['expenseId']))return unknown;
      postings.push(Object.freeze({id:v['id'] as number,kind,commandId:v['commandId'],cashSessionId:v['cashSessionId'] as number,
        expenseId:v['expenseId'] as number,actorId:v['actorId'] as number,amount:money,paymentMethod:method,occurredAt:v['occurredAt']}));
    }
    return Object.freeze({state,projection:Object.freeze({commandId:p['commandId'],operation,cashSessionId:p['cashSessionId'] as number,
      expenseId:p['expenseId'] as number,settlementId:p['settlementId'] as number|null,ledgerEventIds:Object.freeze([...p['ledgerEventIds']]),
      expense,settlement,committedAt:a['committedAt'],postings:Object.freeze(postings),
      snapshot:Object.freeze({id:snap['id'] as string,cutoff:snap['cutoff'],asOf:snap['asOf']})})});
  }
  static posContext(response: unknown): PosContextObservation {
    const unknown = Object.freeze({ state: PosContextState.Unknown, context: null });
    const envelope = this.record(response); const data = this.record(envelope?.['data']);
    if (!envelope || !this.nonempty(envelope['traceId']) || !data) return unknown;
    const state = PosContextState.fromWire(data['state']);
    if (state === PosContextState.Unavailable && envelope['code'] === 503 && data['context'] === null) return { state, context: null };
    const raw = this.record(data['context']);
    if (state !== PosContextState.Available || envelope['code'] !== 200 || !raw || !this.uuid(raw['clientInstanceId']) ||
      !this.positiveId(raw['terminalId']) || !this.nonempty(raw['deviceId']) || String(raw['deviceId']).length > 80 || raw['deviceId'] !== String(raw['deviceId']).trim()) return unknown;
    return Object.freeze({ state, context: Object.freeze({ clientInstanceId: raw['clientInstanceId'] as string, deviceId: raw['deviceId'] as string, terminalId: raw['terminalId'] as number }) });
  }
  static lifecycle(response: unknown): AccountingLifecycleState {
    const envelope = this.record(response); const data = this.record(envelope?.['data']);
    const instant = (value: unknown) => typeof value === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
    if (!envelope || envelope['code'] !== 200 || !this.nonempty(envelope['traceId']) || !data || data['contractVersion'] !== 'V2' || !instant(data['observedAt'])) return AccountingLifecycleState.Unknown;
    const state = AccountingLifecycleState.fromWire(data['state']);
    return (state === AccountingLifecycleState.PreActivation ? data['activationAt'] === null : instant(data['activationAt'])) ? state : AccountingLifecycleState.Unknown;
  }
  static saleAdmission(response: unknown, command: SaleCommand, actorId: number,expectedPayloadHash:string|null=null): SaleAdmission {
    const unknown = { outcome: SaleAdmissionOutcome.Unknown, receipt: null };
    const envelope = this.record(response); const data = this.record(envelope?.['data']);
    if (!envelope || !this.nonempty(envelope['traceId']) || !data) return unknown;
    const outcome = SaleAdmissionOutcome.fromWire(data['outcome']);
    if (outcome !== SaleAdmissionOutcome.Accepted) {
      const expected = outcome === SaleAdmissionOutcome.NotFound ? 404 : outcome === SaleAdmissionOutcome.Unavailable ? 503 : null;
      return expected === envelope['code'] && data['receipt'] === null ? { outcome, receipt: null } : unknown;
    }
    const raw = this.record(data['receipt']); const identity = this.identity(raw); const kind = SaleCommandKind.fromWire(raw?.['kind']);
    if (!raw || !identity || kind === SaleCommandKind.Unknown || ![200, 202].includes(envelope['code'] as number) || data['failure'] !== null ||
      !this.uuid(raw['commandId']) || !this.positiveId(raw['actorId']) || !this.positiveId(raw['cashSessionId']) || !this.positiveId(raw['intentId']) || !this.positiveId(raw['outboxId']) ||
      !/^[0-9a-f]{64}$/.test(String(raw['payloadHash'])) || typeof raw['acceptedAt'] !== 'string' || !/(?:Z|[+-]\d{2}:\d{2})$/.test(raw['acceptedAt']) || !Number.isFinite(Date.parse(raw['acceptedAt']))) return unknown;
    const receipt = Object.freeze({ ...identity, kind, commandId: raw['commandId'] as string, actorId: raw['actorId'] as number,
      cashSessionId: raw['cashSessionId'] as number, payloadHash: raw['payloadHash'] as string, intentId: raw['intentId'] as number,
      outboxId: raw['outboxId'] as number, acceptedAt: raw['acceptedAt'] });
    if(expectedPayloadHash && command.accepts(receipt,actorId,receipt.payloadHash) && receipt.payloadHash!==expectedPayloadHash)
      return {outcome:SaleAdmissionOutcome.Unknown,receipt:null,verification:SaleAdmissionVerification.PayloadMismatch};
    return command.accepts(receipt, actorId,expectedPayloadHash) ? { outcome, receipt,verification:SaleAdmissionVerification.Verified } : unknown;
  }
  static uuid(raw: unknown): raw is string { return typeof raw === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(raw); }
  static commandPath(command: AccountingCommand): string {
    if (command.kind === AccountingCommandKind.Open) return '/cash-sessions';
    if (command.kind === AccountingCommandKind.Close) return `/cash-sessions/${command.aggregateId}/close`;
    if (command.kind === AccountingCommandKind.Expense) return '/expenses';
    if (command.kind === AccountingCommandKind.Capture) return '/payments';
    return `/payments/${command.aggregateId}/reversals`;
  }

  static commandReceipt(response: unknown, commandId: string): CommandReceipt {
    const unknown: CommandReceipt = { outcome: CommandOutcome.Unknown, failure: CommandFailure.Unknown,
      commandId: null, cashSessionId: null, paymentId: null, expenseId: null, settlementId: null, closeSnapshot: null };
    const envelope = this.record(response);
    if (!envelope || !this.nonempty(envelope['traceId']) || typeof envelope['code'] !== 'number') return unknown;
    const raw = this.record(envelope['data']);
    if (!raw) return { ...unknown, failure: CommandFailure.fromWire(envelope['errorCode']) };
    const outcome = CommandOutcome.fromWire(raw['outcome']);
    const failure = CommandFailure.fromWire(raw['failure']);
    if (outcome === CommandOutcome.Committed && (envelope['code'] !== 200 || raw['commandId'] !== commandId ||
      failure !== CommandFailure.None || !this.nonempty(raw['committedAt']) || !Number.isFinite(Date.parse(raw['committedAt'] as string)) ||
      !this.positiveId(raw['cashSessionId']) || !Array.isArray(raw['ledgerEventIds']) ||
      !raw['ledgerEventIds'].every(item => this.positiveId(item)) ||
      new Set(raw['ledgerEventIds']).size !== raw['ledgerEventIds'].length)) return unknown;
    for (const key of ['paymentId', 'expenseId', 'settlementId']) {
      if (raw[key] != null && !this.positiveId(raw[key])) return unknown;
    }
    const close = this.record(raw['closeSnapshot']);
    const declared = TicketMoney.fromDecimal(close?.['declaredCash']);
    const expected = TicketMoney.fromDecimal(close?.['expectedCash']);
    const difference = TicketMoney.fromDecimal(close?.['difference']);
    if (raw['closeSnapshot'] != null && (!close || !declared || declared.cents < 0n ||
      (close['expectedCash'] != null && !expected) || (close['difference'] != null && !difference))) return unknown;
    const coverage = AccountingCoverage.fromWire(close?.['coverage']);
    const reconciliation = ReconciliationOutcome.fromWire(close?.['outcome']);
    if (close && (coverage === AccountingCoverage.Unknown || reconciliation === ReconciliationOutcome.Unknown ||
      (coverage === AccountingCoverage.Complete && (!expected || !difference || !declared ||
        difference.cents !== declared.cents - expected.cents ||
        (difference.cents === 0n ? reconciliation !== ReconciliationOutcome.Balanced :
          difference.cents < 0n ? reconciliation !== ReconciliationOutcome.Shortage : reconciliation !== ReconciliationOutcome.Overage))) ||
      (coverage === AccountingCoverage.LegacyIncomplete && (expected !== null || difference !== null || reconciliation !== ReconciliationOutcome.Unavailable)))) return unknown;
    return Object.freeze({ outcome, failure, commandId: outcome === CommandOutcome.Committed ? commandId : null,
      cashSessionId: this.positiveId(raw['cashSessionId']) ? raw['cashSessionId'] as number : null,
      paymentId: raw['paymentId'] as number ?? null, expenseId: raw['expenseId'] as number ?? null,
      settlementId: raw['settlementId'] as number ?? null,
      committedAt: outcome===CommandOutcome.Committed ? raw['committedAt'] as string : null,
      ledgerEventIds: outcome===CommandOutcome.Committed ? Object.freeze([...(raw['ledgerEventIds'] as number[])]) : Object.freeze([]),
      closeSnapshot: close && declared ? Object.freeze({ declared, expected, difference,
        outcome: reconciliation, coverage }) : null });
  }

  static cashMutationOutcome(response: unknown, httpStatus: number): CashMutationOutcome {
    const raw = PosWireMapper.record(response);
    if (!raw || raw['code'] !== httpStatus || !PosWireMapper.nonempty(raw['traceId']) ||
        (raw['message'] !== null && typeof raw['message'] !== 'string') ||
        (raw['retryable'] !== null && typeof raw['retryable'] !== 'boolean')) return CashMutationOutcome.Unknown;
    const outcome = CashMutationOutcome.fromWire(raw['errorCode']);
    if (outcome.httpStatus !== httpStatus || (outcome.isApplied ? !PosWireMapper.record(raw['data']) : raw['data'] !== null)) return CashMutationOutcome.Unknown;
    return outcome;
  }

  static cashMutationSession(response: unknown): CashSessionData | null {
    const raw = PosWireMapper.payload(response);
    if (!raw || !PosWireMapper.positiveId(raw['id']) || !PosWireMapper.positiveId(raw['terminalId']) ||
        !PosWireMapper.positiveId(raw['cashierId']) || typeof raw['openingCash'] !== 'number' ||
        !Number.isFinite(raw['openingCash']) || raw['openingCash'] < 0 || CashSessionStatus.fromWire(raw['status']) === CashSessionStatus.Unknown) return null;
    return PosWireMapper.cashSession(raw as unknown as CashSessionWire);
  }

  static cashMutationExpense(response: unknown): boolean {
    const raw = PosWireMapper.payload(response);
    return !!raw && PosWireMapper.positiveId(raw['id']) && typeof raw['amount'] === 'number' &&
      Number.isFinite(raw['amount']) && raw['amount'] > 0 && !!PosWireMapper.nonempty(raw['category']);
  }

  static cashSession(raw: CashSessionWire): CashSessionData {
    return { ...raw, status: CashSessionStatus.fromWire(raw.status) };
  }

  static cashSessions(raw: CashSessionWire[] | null | undefined): CashSessionData[] {
    return (raw ?? []).map((item) => PosWireMapper.cashSession(item));
  }

  static workspace(raw: WorkspaceWire): WorkspaceData {
    return { ...raw, persistence: PersistenceMode.fromWire(raw.persistence) };
  }

  static report(raw: ShiftReportWire): ShiftReportData {
    return {
      ...raw,
      formulaName: ReportFormula.fromWire(raw.formulaName),
      periodKind: ReportPeriod.fromWire(raw.periodKind),
    };
  }

  static reportFailure(status: unknown): ReportFailure { return ReportFailure.fromWire(status); }

  static accountingReport(response: unknown, request: AccountingReportRequest): AccountingReport | null {
    const raw = PosWireMapper.payload(response);
    const bounds = PosWireMapper.record(raw?.['bounds']);
    const formula = PosWireMapper.record(raw?.['formula']);
    const completeness = PosWireMapper.record(raw?.['completeness']);
    const totals = PosWireMapper.record(raw?.['totalsByMethod']);
    const instant = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value)) && /(?:Z|[+-]\d{2}:\d{2})$/.test(value);
    if (!raw || !bounds || !formula || !completeness || !totals ||
        !instant(bounds['start']) || !instant(bounds['endExclusive']) || Date.parse(bounds['start']) >= Date.parse(bounds['endExclusive']) ||
        !instant(raw['cutoff']) || !instant(raw['zoneEffectiveAt']) || !PosWireMapper.nonempty(raw['snapshot']) ||
        !PosWireMapper.nonempty(raw['zone']) || !PosWireMapper.nonempty(raw['zoneVersion']) ||
        !PosWireMapper.positiveId(raw['accountingVersion']) || typeof raw['provisional'] !== 'boolean') return null;
    const unknownCoverage: MetricCoverage = Object.freeze({ state: DataCompleteness.Unknown, causes: Object.freeze([CompletenessCause.Unknown]) });
    const coverage = (value: unknown): MetricCoverage => {
      const row = PosWireMapper.record(value);
      const state = DataCompleteness.fromWire(row?.['state']);
      if (!row || !Array.isArray(row['causes']) || state === DataCompleteness.Unknown) return unknownCoverage;
      const causes = row['causes'].map(value => CompletenessCause.fromWire(value));
      if (causes.includes(CompletenessCause.Unknown) || (state === DataCompleteness.Complete) !== (causes.length === 0)) return unknownCoverage;
      return Object.freeze({ state, causes: Object.freeze(causes) });
    };
    const covered = (metric: AccountingMetric, value: unknown, source: MetricCoverage): CoveredValue => {
      const money = TicketMoney.fromDecimal(value);
      const valid = value === null || (!!money && money.cents >= 0n);
      const effective = valid && (source.state !== DataCompleteness.Complete || money !== null) ? source : unknownCoverage;
      return Object.freeze({ metric, value: effective.state.permitsValue ? money : null, coverage: effective });
    };
    const metricCoverage = new Map(AccountingMetric.all.map(metric => [metric, coverage(completeness[metric.wire])] as const));
    // Unknown extra metrics invalidate the coverage claim without rendering their raw keys.
    if (Object.keys(completeness).some(key => AccountingMetric.fromWire(key) === AccountingMetric.Unknown)) {
      AccountingMetric.all.forEach(metric => metricCoverage.set(metric, unknownCoverage));
    }
    const gross = TicketMoney.fromDecimal(raw['grossSales']);
    const discounts = TicketMoney.fromDecimal(raw['discounts']);
    const net = TicketMoney.fromDecimal(raw['netSales']);
    const commercialValid = raw['netSales'] === null || (!!gross && !!discounts && !!net &&
      gross.cents >= 0n && discounts.cents >= 0n && net.cents === gross.cents - discounts.cents);
    const netSales = covered(AccountingMetric.NetSales, raw['netSales'], commercialValid ? metricCoverage.get(AccountingMetric.NetSales)! : unknownCoverage);
    metricCoverage.set(AccountingMetric.NetSales, netSales.coverage);
    const financialFields = ['collected', 'refunds', 'feesPaid', 'expensesPaid'] as const;
    const totalsByMethod = Object.entries(totals).map(([key, value]) => {
      const method = PaymentMethod.fromWire(key);
      const row = PosWireMapper.record(value);
      const values = AccountingMetric.financial.map((metric, index) => covered(metric, row?.[financialFields[index]],
        method === PaymentMethod.Unknown ? unknownCoverage : metricCoverage.get(metric)!));
      const flow = TicketMoney.fromDecimal(row?.['operatingCashFlow']);
      const expected = values.every(item => item.value !== null) ? values[0].value!.cents - values[1].value!.cents - values[2].value!.cents - values[3].value!.cents : null;
      return Object.freeze({ method, values: Object.freeze(values), operatingCashFlow: flow && flow.cents === expected ? flow : null });
    });
    // An omitted method is missing evidence, never a synthetic zero row.
    for (const method of PaymentMethod.selectable) {
      if (!totalsByMethod.some(row => row.method === method)) totalsByMethod.push(Object.freeze({ method,
        values: Object.freeze(AccountingMetric.financial.map(metric => covered(metric, null, unknownCoverage))), operatingCashFlow: null }));
    }
    for (const metric of AccountingMetric.financial) {
      if (totalsByMethod.some(row => row.values.some(value => value.metric === metric && value.coverage.state === DataCompleteness.Unknown))) metricCoverage.set(metric, unknownCoverage);
    }
    const kind = AccountingFormula.fromWire(formula['kind']);
    const version = FormulaVersion.fromWire(formula['version']);
    const formulaValue = TicketMoney.fromDecimal(formula['value']);
    let formulaCoverage = kind === AccountingFormula.Unknown || version === FormulaVersion.Unknown ? unknownCoverage : coverage(formula['completeness']);
    if (kind !== AccountingFormula.Contribution && kind !== AccountingFormula.Unknown) formulaCoverage = Object.freeze({
      state: DataCompleteness.Unavailable, causes: Object.freeze([CompletenessCause.FormulaNotApproved]) });
    const contributionInputsKnown = [AccountingMetric.NetSales, AccountingMetric.FeesPaid, AccountingMetric.ExpensesPaid]
      .every(metric => metricCoverage.get(metric)?.state !== DataCompleteness.Unknown);
    if (kind === AccountingFormula.Contribution && !contributionInputsKnown) formulaCoverage = unknownCoverage;
    if (kind === AccountingFormula.Contribution && formulaCoverage.state.permitsValue && !formulaValue) formulaCoverage = unknownCoverage;
    metricCoverage.set(AccountingMetric.Contribution, formulaCoverage);
    const coverageRows = AccountingMetric.all.map(metric => Object.freeze({ metric, value: null, coverage: metricCoverage.get(metric)! }));
    let reconciliation: Reconciliation | null = null;
    if (raw['reconciliation'] !== null) {
      const row = PosWireMapper.record(raw['reconciliation']);
      const expectedCash = TicketMoney.fromDecimal(row?.['expectedCash']);
      const declaredCash = TicketMoney.fromDecimal(row?.['declaredCash']);
      const difference = TicketMoney.fromDecimal(row?.['difference']);
      let outcome = ReconciliationOutcome.fromWire(row?.['outcome']);
      const differenceValid = !!expectedCash && !!declaredCash && !!difference && difference.cents === declaredCash.cents - expectedCash.cents;
      const outcomeValid = (outcome === ReconciliationOutcome.Unavailable && row?.['difference'] === null &&
        (raw['provisional'] === true || row?.['expectedCash'] === null)) ||
        (differenceValid && ((outcome === ReconciliationOutcome.Balanced && difference!.cents === 0n) ||
          (outcome === ReconciliationOutcome.Shortage && difference!.cents < 0n) || (outcome === ReconciliationOutcome.Overage && difference!.cents > 0n)));
      if (!outcomeValid || (declaredCash && declaredCash.cents < 0n)) outcome = ReconciliationOutcome.Unknown;
      reconciliation = Object.freeze({ expectedCash: outcome === ReconciliationOutcome.Unknown ? null : expectedCash,
        declaredCash: declaredCash && declaredCash.cents >= 0n ? declaredCash : null,
        difference: outcomeValid && outcome !== ReconciliationOutcome.Unavailable ? difference : null, outcome,
        localWatermark: typeof row?.['localWatermark'] === 'number' && Number.isSafeInteger(row['localWatermark']) && row['localWatermark'] >= 0 ? row['localWatermark'] : null });
    }
    const report: AccountingReport = Object.freeze({ period: ReportPeriod.fromWire(raw['periodKind']),
      cashSessionId: PosWireMapper.positiveId(raw['cashSessionId']) ? raw['cashSessionId'] as number : null,
      localDate: typeof raw['localDate'] === 'string' ? raw['localDate'] : null,
      start: bounds['start'], endExclusive: bounds['endExclusive'], cutoff: raw['cutoff'], snapshot: raw['snapshot'] as string,
      zone: raw['zone'] as string, zoneVersion: raw['zoneVersion'] as string, zoneEffectiveAt: raw['zoneEffectiveAt'],
      accountingVersion: raw['accountingVersion'] as number, provisional: raw['provisional'],
      grossSales: netSales.coverage.state.permitsValue ? gross : null,
      discounts: netSales.coverage.state.permitsValue ? discounts : null,
      netSales, coverage: Object.freeze(coverageRows), totalsByMethod: Object.freeze(totalsByMethod),
      formula: Object.freeze({ kind, version, value: kind === AccountingFormula.Contribution && formulaCoverage.state.permitsValue ? formulaValue : null, coverage: formulaCoverage }), reconciliation });
    return request.matches(report) ? report : null;
  }

  static saleStatus(raw: { status: unknown } | null | undefined): SaleStatus {
    return SaleStatus.fromWire(raw?.status);
  }

  static paymentStatus(raw: { status: unknown } | null | undefined): PaymentStatus {
    return PaymentStatus.fromWire(raw?.status);
  }

  static ticket(response: unknown, expected: TicketIdentity, semantics = PaymentLedgerSemantics.Legacy): TicketSnapshot {
    const raw = PosWireMapper.payload(response);
    const identity = PosWireMapper.identity(raw);
    const total = TicketMoney.fromDecimal(raw?.['totalAmount']);
    const pending = TicketMoney.fromDecimal(raw?.['pendingAmount']);
    const coverage = PaymentCoverage.fromWire(raw?.['paymentCoverage']);
    const history = typeof raw?.['hasPaymentHistory'] === 'boolean' ? raw['hasPaymentHistory'] : null;
    const receipt = PosWireMapper.nonempty(raw?.['receipt']);
    const reservationRef = PosWireMapper.nonempty(raw?.['reservationRef']);
    const balanceValid = !!total && !!pending && total.cents > 0n && pending.cents >= 0n && pending.cents <= total.cents;
    const coverageValid = semantics !== PaymentLedgerSemantics.Unknown && balanceValid && (
      (coverage === PaymentCoverage.Unpaid && pending!.cents === total!.cents && (history === false || semantics === PaymentLedgerSemantics.Net && history === true)) ||
      (coverage === PaymentCoverage.Partial && pending!.cents > 0n && pending!.cents < total!.cents && history === true) ||
      (coverage === PaymentCoverage.Paid && pending!.cents === 0n && history === true));
    const evidenceValid = !!identity && sameTicketIdentity(identity, expected) && raw?.['evidenceValid'] === true &&
      !!receipt && !!reservationRef && typeof raw?.['blocked'] === 'boolean' && typeof raw?.['retired'] === 'boolean';
    return Object.freeze({
      ledgerSemantics: semantics,
      identity: identity ?? expected,
      status: SaleStatus.fromWire(raw?.['status']),
      evidenceValid,
      receipt,
      reservationRef,
      total,
      pending,
      coverage: coverageValid ? coverage : PaymentCoverage.InvalidUnknown,
      hasPaymentHistory: history,
      blocked: raw?.['blocked'] !== false,
      retired: raw?.['retired'] !== false,
    });
  }

  static capturedPayment(response: unknown, expected: PaymentAttempt): CapturedPayment | null {
    const raw = PosWireMapper.payload(response);
    const identity = PosWireMapper.identity(raw);
    const amount = TicketMoney.fromDecimal(raw?.['amount']);
    const fee = TicketMoney.fromDecimal(raw?.['feeAmount']);
    const paymentId = raw?.['paymentId'];
    const status = PaymentStatus.fromWire(raw?.['status']);
    if (!identity || !sameTicketIdentity(identity, expected.identity) ||
        typeof paymentId !== 'number' || !Number.isSafeInteger(paymentId) || paymentId <= 0 || status !== PaymentStatus.Captured ||
        !amount || amount.cents !== expected.amount.cents || !fee || fee.cents !== expected.fee.cents) return null;
    return Object.freeze({ paymentId, status, amount, fee });
  }

  static durablePage(response: unknown): DurableSalePage | null {
    const raw = PosWireMapper.payload(response);
    if (!raw || !Array.isArray(raw['items']) || (raw['nextCursor'] !== null && typeof raw['nextCursor'] !== 'string')) return null;
    const items = raw['items'].map(item => PosWireMapper.durableSummary(item));
    if (items.some(item => !item)) return null;
    return Object.freeze({ items: Object.freeze(items as DurableSaleSummary[]), nextCursor: raw['nextCursor'] as string | null });
  }

  private static durableSummary(value: unknown): DurableSaleSummary | null {
    const raw = PosWireMapper.record(value);
    const identity = PosWireMapper.identity(raw);
    if (!raw || !identity || !PosWireMapper.positiveId(raw['cashSessionId']) || !PosWireMapper.positiveId(raw['cashierId'])) return null;
    return Object.freeze({ identity, cashSessionId: raw['cashSessionId'] as number, cashierId: raw['cashierId'] as number,
      createdBy: PosWireMapper.positiveId(raw['createdBy']) ? raw['createdBy'] as number : null,
      status: DurableSaleState.fromWire(raw['status']), total: TicketMoney.fromDecimal(raw['totalAmount']) });
  }

  static durableDetail(response: unknown, operationId: string): DurableSaleDetail | null {
    const raw = PosWireMapper.payload(response);
    const summary = PosWireMapper.durableSummary(raw);
    if (!raw || !summary || summary.identity.operationId !== operationId) return null;
    const pending = TicketMoney.fromDecimal(raw['pendingAmount']);
    const coverage = PaymentCoverage.fromWire(raw['paymentCoverage']);
    const history = typeof raw['hasPaymentHistory'] === 'boolean' ? raw['hasPaymentHistory'] : null;
    const balance = !!summary.total && summary.total.cents > 0n && !!pending && pending.cents >= 0n && pending.cents <= summary.total.cents;
    const validCoverage = balance && ((coverage === PaymentCoverage.Unpaid && pending!.cents === summary.total!.cents && history !== null) ||
      (coverage === PaymentCoverage.Partial && pending!.cents > 0n && pending!.cents < summary.total!.cents && history === true) ||
      (coverage === PaymentCoverage.Paid && pending!.cents === 0n && history === true));
    const lines = Array.isArray(raw['lines']) ? raw['lines'].map(value => {
      const line = PosWireMapper.record(value);
      return Object.freeze({ sku: PosWireMapper.nonempty(line?.['sku']) ?? '—', productName: PosWireMapper.nonempty(line?.['productName']) ?? 'Sin descripción',
        quantity: typeof line?.['quantity'] === 'number' ? line['quantity'] : 0, total: TicketMoney.fromDecimal(line?.['totalAmount']) });
    }) : [];
    const paymentRows = Array.isArray(raw['payments']) ? raw['payments'].map(value => {
      const payment = PosWireMapper.record(value);
      return Object.freeze({ paymentId: PosWireMapper.positiveId(payment?.['paymentId']) ? payment!['paymentId'] as number : 0,
        status: PaymentStatus.fromWire(payment?.['status']), method: PaymentMethod.fromWire(payment?.['method']),
        originalPaymentId: payment?.['originalPaymentId']===null ? null : PosWireMapper.positiveId(payment?.['originalPaymentId']) ? payment!['originalPaymentId'] as number : 0,
        amount: TicketMoney.fromDecimal(payment?.['amount']), fee: TicketMoney.fromDecimal(payment?.['feeAmount']) });
    }) : [];
    const payments = paymentRows.map(payment=>Object.freeze({...payment,reversibility:PaymentReversibility.forPayment(payment,paymentRows)}));
    const allowedActions = Array.isArray(raw['allowedActions']) ? raw['allowedActions'].map(AllowedAction.fromWire) : [AllowedAction.Unknown];
    const command = raw['pendingCommand'] == null ? null : DurableCommandKind.fromWire(PosWireMapper.record(raw['pendingCommand'])?.['kind']);
    const receipt = PosWireMapper.nonempty(raw['receipt']);
    const reservationRef = PosWireMapper.nonempty(raw['reservationRef']);
    const net = payments.reduce((sum,payment) => payment.amount ? sum + (payment.status === PaymentStatus.Captured ? payment.amount.cents : payment.status === PaymentStatus.Refunded ? -payment.amount.cents : 0n) : sum,0n);
    const ledgerValid = PaymentReversibility.validLedger(payments) && !!summary.total && !!pending && net === summary.total.cents - pending.cents && (history === true ? payments.length > 0 : payments.length === 0);
    const valid = summary.createdBy !== null && !!validCoverage && ledgerValid && raw['evidenceValid'] === true && raw['blocked'] === false && raw['retired'] === false &&
      !allowedActions.includes(AllowedAction.Unknown) && Array.isArray(raw['lines']) && Array.isArray(raw['payments']) &&
      raw['lines'].every(value => { const line = PosWireMapper.record(value); return !!PosWireMapper.nonempty(line?.['sku']) && !!PosWireMapper.nonempty(line?.['productName']); }) &&
      lines.length > 0 && lines.every(line => line.quantity > 0 && Number.isSafeInteger(line.quantity) && !!line.total && line.total.cents >= 0n) &&
      payments.every(payment => payment.paymentId > 0 && (payment.status === PaymentStatus.Captured || payment.status === PaymentStatus.Refunded) && payment.method !== PaymentMethod.Unknown &&
        !!payment.amount && payment.amount.cents > 0n && !!payment.fee && payment.fee.cents >= 0n) &&
      new Set(payments.map(payment => payment.paymentId)).size === payments.length && command !== DurableCommandKind.Unknown && !!receipt && !!reservationRef;
    return Object.freeze({ ...summary, pending, coverage: validCoverage ? coverage : PaymentCoverage.InvalidUnknown, hasPaymentHistory: history,
      lines: Object.freeze(lines), payments: Object.freeze(payments), allowedActions: Object.freeze(allowedActions), valid, receipt, reservationRef, pendingCommand: command });
  }

  private static record(value: unknown): Record<string, unknown> | null {
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
  }
  private static positiveId(value: unknown): boolean { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0; }

  private static payload(response: unknown): Record<string, unknown> | null {
    if (!response || typeof response !== 'object') return null;
    const envelope = response as BaseResponse<unknown>;
    if (envelope.code !== 200 || envelope.errorCode !== null || !PosWireMapper.nonempty(envelope.traceId) ||
        (envelope.message !== null && typeof envelope.message !== 'string') ||
        (envelope.retryable !== null && typeof envelope.retryable !== 'boolean') ||
        !envelope.data || typeof envelope.data !== 'object' || Array.isArray(envelope.data)) return null;
    return envelope.data as Record<string, unknown>;
  }

  private static nonempty(raw: unknown): string | null {
    return typeof raw === 'string' && raw.trim().length > 0 ? raw : null;
  }

  private static identity(raw: Record<string, unknown> | null): TicketIdentity | null {
    const clientInstanceId = PosWireMapper.nonempty(raw?.['clientInstanceId']);
    const deviceId = PosWireMapper.nonempty(raw?.['deviceId']);
    const saleId = PosWireMapper.nonempty(raw?.['saleId']);
    const operationId = PosWireMapper.nonempty(raw?.['operationId']);
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    return clientInstanceId && deviceId && saleId && operationId && uuid.test(clientInstanceId) && uuid.test(saleId) && uuid.test(operationId)
      ? Object.freeze({ clientInstanceId, deviceId, saleId, operationId }) : null;
  }
}
