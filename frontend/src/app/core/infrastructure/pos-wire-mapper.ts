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
}
