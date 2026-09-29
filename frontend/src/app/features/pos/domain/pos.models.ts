import { CashSessionStatus, PaymentStatus, PosPaymentMethod, ReportFormula, ReportPeriod, SaleStatus } from './closed-status';

export { StaffRole, roleCapabilities } from './closed-status';
export type { RoleCapabilities } from './closed-status';

export const LOCAL_CLIENT_INSTANCE_ID = '11111111-1111-1111-1111-111111111111';

export interface CatalogItem {
  readonly sku: string;
  readonly name: string;
  readonly variantId: string;
  readonly priceVersion: string | null;
  readonly unitPrice: number | null;
}

export interface CatalogSnapshot {
  readonly version: string | null;
  readonly stale: boolean;
  readonly importedAt: string | null;
  readonly validUntil: string | null;
  readonly items: readonly CatalogItem[];
}

export interface CashSession {
  readonly id: number;
  readonly terminalId: number;
  readonly cashierId: number;
  readonly status: CashSessionStatus;
  readonly openingCash: number;
  readonly closingCashDeclared: number | null;
}

export interface WorkspaceSnapshot {
  readonly terminalId: number;
  readonly cashierId: number;
  readonly persistence: string;
}

export interface TicketLineDraft {
  readonly sku: string;
  readonly productName: string;
  readonly variantId: string;
  readonly priceVersion: string;
  readonly quantity: number;
  readonly originalUnitPrice: number;
  readonly discountAmount: number;
}

export interface RecordedPayment {
  readonly id: number;
  readonly method: PosPaymentMethod;
  readonly amount: number;
  readonly feeAmount: number;
  readonly status: PaymentStatus;
}

export interface SaleSnapshot {
  readonly saleId: string;
  readonly operationId: string;
  readonly clientInstanceId: string;
  readonly deviceId: string;
  readonly cashSessionId: number;
  readonly status: SaleStatus;
  readonly receipt: string | null;
  readonly reservationRef: string | null;
  readonly lines: readonly TicketLineDraft[];
  readonly payments: readonly RecordedPayment[];
}

export interface ExpenseLog {
  readonly id: number;
  readonly cashSessionId: number;
  readonly category: string;
  readonly amount: number;
  readonly reason: string;
}

export interface ShiftReport {
  readonly grossSales: number;
  readonly discounts: number;
  readonly netSales: number;
  readonly refunds: number;
  readonly collected: number;
  readonly feesPaid: number;
  readonly expensesPaid: number;
  readonly operatingCashFlow: number;
  readonly margin: number | null;
  readonly formulaName: ReportFormula;
  readonly fiscalResult: boolean;
  readonly periodKind: ReportPeriod;
}

export interface TicketPrompt {
  readonly token: number;
  readonly kind: 'stock' | 'stale';
  readonly availableQuantity: number | null;
}

