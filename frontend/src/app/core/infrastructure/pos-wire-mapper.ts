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
