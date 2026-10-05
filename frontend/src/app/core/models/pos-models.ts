import {
  CashSessionStatus,
  PersistenceMode,
  ReportFormula,
  ReportPeriod,
} from '../domain/pos-types';

export interface CashSessionWire {
  readonly id: number;
  readonly terminalId: number;
  readonly cashierId: number;
  readonly status: unknown;
  readonly openingCash: number;
}

export interface CashSessionData extends Omit<CashSessionWire, 'status'> {
  readonly status: CashSessionStatus;
}

export interface WorkspaceWire {
  readonly terminalId: number;
  readonly cashierId: number;
  readonly persistence: unknown;
}

export interface WorkspaceData extends Omit<WorkspaceWire, 'persistence'> {
  readonly persistence: PersistenceMode;
}

export interface ShiftReportWire {
  readonly grossSales: number;
  readonly discounts: number;
  readonly netSales: number;
  readonly refunds: number;
  readonly collected: number;
  readonly feesPaid: number;
  readonly expensesPaid: number;
  readonly operatingCashFlow: number;
  readonly margin: number | null;
  readonly formulaName: unknown;
  readonly fiscalResult: boolean;
  readonly periodKind: unknown;
}

export interface ShiftReportData extends Omit<ShiftReportWire, 'formulaName' | 'periodKind'> {
  readonly formulaName: ReportFormula;
  readonly periodKind: ReportPeriod;
}
