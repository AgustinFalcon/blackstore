import {
  CashSessionStatus,
  PersistenceMode,
  ReportFormula,
  ReportPeriod,
} from '../domain/pos-types';

export interface CashSessionWire {
  id: number;
  terminalId: number;
  cashierId: number;
  status: unknown;
  openingCash: number;
}

export interface CashSessionData extends Omit<CashSessionWire, 'status'> {
  status: CashSessionStatus;
}

export interface WorkspaceWire {
  terminalId: number;
  cashierId: number;
  persistence: unknown;
}

export interface WorkspaceData extends Omit<WorkspaceWire, 'persistence'> {
  persistence: PersistenceMode;
}

export interface ShiftReportWire {
  grossSales: number;
  discounts: number;
  netSales: number;
  refunds: number;
  collected: number;
  feesPaid: number;
  expensesPaid: number;
  operatingCashFlow: number;
  margin: number | null;
  formulaName: unknown;
  fiscalResult: boolean;
  periodKind: unknown;
}

export interface ShiftReportData extends Omit<ShiftReportWire, 'formulaName' | 'periodKind'> {
  formulaName: ReportFormula;
  periodKind: ReportPeriod;
}
