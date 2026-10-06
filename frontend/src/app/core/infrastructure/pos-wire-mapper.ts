import {
  CashSessionStatus,
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
import { CapturedPayment, PaymentAttempt, PaymentCoverage, TicketIdentity, TicketMoney, TicketSnapshot, sameTicketIdentity } from '../domain/ticket-transition';
import { PaymentMethod } from '../domain/pos-types';
import { AllowedAction, DurableCommandKind, DurableSaleDetail, DurableSalePage, DurableSaleState, DurableSaleSummary } from '../domain/durable-sale';

export class PosWireMapper {
  private constructor() {}

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

  static saleStatus(raw: { status: unknown } | null | undefined): SaleStatus {
    return SaleStatus.fromWire(raw?.status);
  }

  static paymentStatus(raw: { status: unknown } | null | undefined): PaymentStatus {
    return PaymentStatus.fromWire(raw?.status);
  }

  static ticket(response: unknown, expected: TicketIdentity): TicketSnapshot {
    const raw = PosWireMapper.payload(response);
    const identity = PosWireMapper.identity(raw);
    const total = TicketMoney.fromDecimal(raw?.['totalAmount']);
    const pending = TicketMoney.fromDecimal(raw?.['pendingAmount']);
    const coverage = PaymentCoverage.fromWire(raw?.['paymentCoverage']);
    const history = typeof raw?.['hasPaymentHistory'] === 'boolean' ? raw['hasPaymentHistory'] : null;
    const receipt = PosWireMapper.nonempty(raw?.['receipt']);
    const reservationRef = PosWireMapper.nonempty(raw?.['reservationRef']);
    const balanceValid = !!total && !!pending && total.cents > 0n && pending.cents >= 0n && pending.cents <= total.cents;
    const coverageValid = balanceValid && (
      (coverage === PaymentCoverage.Unpaid && pending!.cents === total!.cents && history === false) ||
      (coverage === PaymentCoverage.Partial && pending!.cents > 0n && pending!.cents < total!.cents && history === true) ||
      (coverage === PaymentCoverage.Paid && pending!.cents === 0n && history === true));
    const evidenceValid = !!identity && sameTicketIdentity(identity, expected) && raw?.['evidenceValid'] === true &&
      !!receipt && !!reservationRef && typeof raw?.['blocked'] === 'boolean' && typeof raw?.['retired'] === 'boolean';
    return Object.freeze({
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
    const validCoverage = balance && ((coverage === PaymentCoverage.Unpaid && pending!.cents === summary.total!.cents && history === false) ||
      (coverage === PaymentCoverage.Partial && pending!.cents > 0n && pending!.cents < summary.total!.cents && history === true) ||
      (coverage === PaymentCoverage.Paid && pending!.cents === 0n && history === true));
    const lines = Array.isArray(raw['lines']) ? raw['lines'].map(value => {
      const line = PosWireMapper.record(value);
      return Object.freeze({ sku: PosWireMapper.nonempty(line?.['sku']) ?? '—', productName: PosWireMapper.nonempty(line?.['productName']) ?? 'Sin descripción',
        quantity: typeof line?.['quantity'] === 'number' ? line['quantity'] : 0, total: TicketMoney.fromDecimal(line?.['totalAmount']) });
    }) : [];
    const payments = Array.isArray(raw['payments']) ? raw['payments'].map(value => {
      const payment = PosWireMapper.record(value);
      return Object.freeze({ paymentId: PosWireMapper.positiveId(payment?.['paymentId']) ? payment!['paymentId'] as number : 0,
        status: PaymentStatus.fromWire(payment?.['status']), method: PaymentMethod.fromWire(payment?.['method']),
        amount: TicketMoney.fromDecimal(payment?.['amount']), fee: TicketMoney.fromDecimal(payment?.['feeAmount']) });
    }) : [];
    const allowedActions = Array.isArray(raw['allowedActions']) ? raw['allowedActions'].map(AllowedAction.fromWire) : [AllowedAction.Unknown];
    const command = raw['pendingCommand'] == null ? null : DurableCommandKind.fromWire(PosWireMapper.record(raw['pendingCommand'])?.['kind']);
    const receipt = PosWireMapper.nonempty(raw['receipt']);
    const reservationRef = PosWireMapper.nonempty(raw['reservationRef']);
    const valid = !!validCoverage && raw['evidenceValid'] === true && raw['blocked'] === false && raw['retired'] === false &&
      !allowedActions.includes(AllowedAction.Unknown) && Array.isArray(raw['lines']) && Array.isArray(raw['payments']) &&
      raw['lines'].every(value => { const line = PosWireMapper.record(value); return !!PosWireMapper.nonempty(line?.['sku']) && !!PosWireMapper.nonempty(line?.['productName']); }) &&
      lines.length > 0 && lines.every(line => line.quantity > 0 && Number.isSafeInteger(line.quantity) && !!line.total && line.total.cents >= 0n) &&
      payments.every(payment => payment.paymentId > 0 && payment.status !== PaymentStatus.Unknown && payment.method !== PaymentMethod.Unknown &&
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
