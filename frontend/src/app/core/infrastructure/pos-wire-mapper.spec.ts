import {
  CashSessionStatus,
  PaymentStatus,
  PersistenceMode,
  ReportFormula,
  ReportPeriod,
  SaleStatus,
} from '../domain/pos-types';
import { PosWireMapper } from './pos-wire-mapper';
import { PaymentAttempt, PaymentCoverage, TicketIdentity, TicketMoney } from '../domain/ticket-transition';
import { PaymentMethod } from '../domain/pos-types';

describe('PosWireMapper', () => {
  it('translates cash sessions and workspace at the HTTP boundary', () => {
    const session = PosWireMapper.cashSession({
      id: 9,
      terminalId: 10,
      cashierId: 7,
      status: 'OPEN',
      openingCash: 125,
    });
    const workspace = PosWireMapper.workspace({ terminalId: 10, cashierId: 7, persistence: 'postgresql' });

    expect(session.status).toBe(CashSessionStatus.Open);
    expect(session.openingCash).toBe(125);
    expect(workspace.persistence).toBe(PersistenceMode.PostgreSql);
  });

  it('maps unknown cash state to the fixed Unknown case without echoing raw text', () => {
    const session = PosWireMapper.cashSession({
      id: 9,
      terminalId: 10,
      cashierId: 7,
      status: 'SERVER_ADDED_STATE',
      openingCash: 125,
    });

    expect(session.status).toBe(CashSessionStatus.Unknown);
    expect(session.status.label).toBe('estado desconocido');
    expect(session.status.label).not.toContain('SERVER_ADDED_STATE');
  });

  it('translates sale, payment and report metadata', () => {
    expect(PosWireMapper.saleStatus({ status: 'COMMITTED' })).toBe(SaleStatus.Committed);
    expect(PosWireMapper.paymentStatus({ status: 'REFUNDED' })).toBe(PaymentStatus.Refunded);
    const report = PosWireMapper.report({
      grossSales: 20,
      discounts: 2,
      netSales: 18,
      refunds: 0,
      collected: 18,
      feesPaid: 0.5,
      expensesPaid: 0,
      operatingCashFlow: 17.5,
      margin: null,
      formulaName: 'CONTRIBUTION',
      fiscalResult: false,
      periodKind: 'DAY',
    });

    expect(report.formulaName).toBe(ReportFormula.Contribution);
    expect(report.periodKind).toBe(ReportPeriod.Day);
    expect(report.netSales).toBe(18);
  });

  it('uses neutral labels for unknown sale and payment states without echoing the wire', () => {
    const sale = PosWireMapper.saleStatus({ status: 'NEW_SERVER_SALE_STATE' });
    const payment = PosWireMapper.paymentStatus({ status: 'NEW_SERVER_PAYMENT_STATE' });

    expect(sale).toBe(SaleStatus.Unknown);
    expect(payment).toBe(PaymentStatus.Unknown);
    expect(sale.label).toBe('estado desconocido');
    expect(payment.label).toBe('estado desconocido');
    expect(sale.label).not.toContain('NEW_SERVER_SALE_STATE');
    expect(payment.label).not.toContain('NEW_SERVER_PAYMENT_STATE');
  });
});

describe('PosWireMapper ticket/payment correlation (T01/T02/T05)', () => {
  const identity: TicketIdentity = {
    clientInstanceId: '11111111-1111-1111-1111-111111111111', deviceId: 'terminal-1',
    saleId: '22222222-2222-2222-2222-222222222222', operationId: '33333333-3333-3333-3333-333333333333',
  };
  const envelope = (data: unknown) => ({ code: 200, traceId: 'trace', errorCode: null, message: null, retryable: null, data });
  const ticket = { ...identity, status: 'RESERVED', receipt: 'receipt', reservationRef: 'reservation',
    evidenceValid: true, totalAmount: '18.00', pendingAmount: '18.00', paymentCoverage: 'UNPAID', hasPaymentHistory: false, blocked: false, retired: false };
  const attempt: PaymentAttempt = { identity, method: PaymentMethod.Cash, amount: TicketMoney.fromDecimal('10')!, fee: TicketMoney.fromDecimal('0.50')! };
  const payment = { ...identity, paymentId: 1, status: 'CAPTURED', amount: '10.000', feeAmount: '0.50' };

  it('maps explicit valid snapshot with closed coverage', () => {
    const result = PosWireMapper.ticket(envelope(ticket), identity);
    expect(result.evidenceValid).toBeTrue();
    expect(result.status).toBe(SaleStatus.Reserved);
    expect(result.coverage).toBe(PaymentCoverage.Unpaid);
  });

  it('denies missing, invalid envelope, mismatched identity and missing evidence', () => {
    for (const response of [null, envelope(null), { ...envelope(ticket), code: 409 }, { ...envelope(ticket), errorCode: 'ERROR' },
      { ...envelope(ticket), traceId: '' }, { ...envelope(ticket), retryable: undefined }, { ...envelope(ticket), message: undefined }, envelope({ ...ticket, operationId: identity.saleId }),
      envelope({ ...ticket, clientInstanceId: undefined }), envelope({ ...ticket, saleId: {} }),
      envelope({ ...ticket, receipt: null }), envelope({ ...ticket, reservationRef: '' }),
      envelope({ ...ticket, evidenceValid: undefined }), envelope({ ...ticket, blocked: undefined })]) {
      expect(PosWireMapper.ticket(response, identity).evidenceValid).withContext(JSON.stringify(response)).toBeFalse();
    }
  });

  it('fails closed for unknown states and invalid/contradictory coverage money', () => {
    for (const status of ['FUTURE', '', undefined, null, {}]) {
      expect(PosWireMapper.ticket(envelope({ ...ticket, status }), identity).status).toBe(SaleStatus.Unknown);
    }
    for (const changes of [{ paymentCoverage: 'FUTURE' }, { paymentCoverage: null }, { paymentCoverage: {} },
      { totalAmount: '18.001' }, { pendingAmount: undefined }, { pendingAmount: 19 }, { paymentCoverage: 'PAID' },
      { hasPaymentHistory: true }, { hasPaymentHistory: null }]) {
      expect(PosWireMapper.ticket(envelope({ ...ticket, ...changes }), identity).coverage).toBe(PaymentCoverage.InvalidUnknown);
    }
  });

  it('accepts only Captured with exact paymentId, full identity, amount and fee', () => {
    expect(PosWireMapper.capturedPayment(envelope(payment), attempt)?.paymentId).toBe(1);
    for (const changes of [{ paymentId: undefined }, { paymentId: 0 }, { paymentId: -1 }, { paymentId: 1.1 }, { paymentId: '1' },
      { status: 'PENDING' }, { status: 'FUTURE' }, { status: null }, { status: {} },
      { operationId: identity.saleId }, { deviceId: 'other' }, { clientInstanceId: undefined }, { saleId: identity.operationId },
      { amount: '9.99' }, { amount: '10.005' }, { feeAmount: undefined }, { feeAmount: '0.51' }, { feeAmount: '0.501' }]) {
      expect(PosWireMapper.capturedPayment(envelope({ ...payment, ...changes }), attempt)).withContext(JSON.stringify(changes)).toBeNull();
    }
    expect(PosWireMapper.capturedPayment({ ...envelope(payment), code: 500 }, attempt)).toBeNull();
    expect(PosWireMapper.capturedPayment(envelope(null), attempt)).toBeNull();
  });
});
