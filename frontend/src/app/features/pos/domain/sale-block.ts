import { CatalogSnapshot, ExpenseLog, RecordedPayment, SaleSnapshot } from './pos.models';

export function catalogIsClosed(snapshot: CatalogSnapshot | null, unavailable: boolean): boolean {
  if (unavailable || !snapshot) return true;
  return snapshot.stale || snapshot.items.length === 0;
}

export function newSaleBlockReason(input: {
  readonly loading: boolean;
  readonly catalog: CatalogSnapshot | null;
  readonly catalogUnavailable: boolean;
  readonly hasOpenSession: boolean;
  readonly stockBlocked: boolean;
  readonly writesDisabled: boolean;
}): string | null {
  if (input.writesDisabled) return 'El puesto no está habilitado. No se puede escribir.';
  if (input.loading) return 'Comprobando caja y catálogo…';
  if (input.catalogUnavailable || !input.catalog) return 'Catálogo no disponible. No inicies una venta nueva.';
  if (input.catalog.stale || input.catalog.items.length === 0) {
    return 'Catálogo vencido. No inicies una venta nueva.';
  }
  if (input.stockBlocked) return 'Sin stock vendible. No inicies una venta nueva.';
  if (!input.hasOpenSession) return 'No hay sesión de caja abierta.';
  return null;
}

export function reportHasMovement(report: {
  readonly grossSales: number;
  readonly discounts: number;
  readonly netSales: number;
  readonly refunds: number;
  readonly collected: number;
  readonly feesPaid: number;
  readonly expensesPaid: number;
  readonly operatingCashFlow: number;
}): boolean {
  return [
    report.grossSales,
    report.discounts,
    report.netSales,
    report.refunds,
    report.collected,
    report.feesPaid,
    report.expensesPaid,
    report.operatingCashFlow,
  ].some((value) => value !== 0);
}

export function cashCollectedForSession(sessionId: number, sales: readonly SaleSnapshot[]): number {
  return sales
    .filter((sale) => sale.cashSessionId === sessionId)
    .flatMap((sale) => sale.payments)
    .filter((payment) => payment.method.cash && payment.status.collected)
    .reduce((sum, payment) => sum + payment.amount, 0);
}

export function expectedDrawer(input: {
  readonly openingCash: number;
  readonly expenses: readonly Pick<ExpenseLog, 'amount'>[];
  readonly cashIn: number;
}): number {
  const expenses = input.expenses.reduce((sum, item) => sum + item.amount, 0);
  return input.openingCash + input.cashIn - expenses;
}

export function feesOf(payments: readonly Pick<RecordedPayment, 'feeAmount'>[]): number {
  return payments.reduce((sum, payment) => sum + payment.feeAmount, 0);
}
