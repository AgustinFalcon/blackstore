import {
  CashSessionStatus,
  PaymentStatus,
  PersistenceMode,
  ReportFormula,
  ReportPeriod,
  SaleStatus,
} from '../domain/pos-types';
import { PosWireMapper } from './pos-wire-mapper';

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
