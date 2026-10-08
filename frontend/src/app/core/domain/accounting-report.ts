import { PaymentMethod, ReportPeriod } from './pos-types';
import { TicketMoney } from './ticket-transition';

export class DataCompleteness {
  static readonly Complete = new DataCompleteness('COMPLETE', 'completo', true);
  static readonly Partial = new DataCompleteness('PARTIAL', 'parcial: sólo fuentes acreditadas', true);
  static readonly LegacyIncomplete = new DataCompleteness('LEGACY_INCOMPLETE', 'histórico sin cobertura suficiente');
  static readonly Unavailable = new DataCompleteness('UNAVAILABLE', 'no disponible');
  static readonly Unknown = new DataCompleteness('UNKNOWN', 'completitud desconocida');
  private constructor(readonly wire: string, readonly label: string, readonly permitsValue = false) {}
  static fromWire(raw: unknown): DataCompleteness {
    return [this.Complete, this.Partial, this.LegacyIncomplete, this.Unavailable].find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class CompletenessCause {
  static readonly LegacyActivity = new CompletenessCause('LEGACY_ACTIVITY', 'Actividad histórica sin hechos contables acreditados');
  static readonly BeforeActivation = new CompletenessCause('BEFORE_ACTIVATION', 'Período anterior a la activación contable');
  static readonly MissingSource = new CompletenessCause('MISSING_SOURCE', 'Falta evidencia de una fuente');
  static readonly UnknownSource = new CompletenessCause('UNKNOWN_SOURCE', 'Fuente no reconocida');
  static readonly FormulaNotApproved = new CompletenessCause('FORMULA_NOT_APPROVED', 'Fórmula sin inputs aprobados');
  static readonly UnknownFormula = new CompletenessCause('UNKNOWN_FORMULA', 'Fórmula o versión no reconocida');
  static readonly Unknown = new CompletenessCause('UNKNOWN', 'Causa no reconocida');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): CompletenessCause {
    return [this.LegacyActivity, this.BeforeActivation, this.MissingSource, this.UnknownSource, this.FormulaNotApproved, this.UnknownFormula]
      .find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class AccountingMetric {
  static readonly NetSales = new AccountingMetric('NET_SALES', 'Ventas netas');
  static readonly Collected = new AccountingMetric('COLLECTED', 'Cobrado');
  static readonly Refunds = new AccountingMetric('REFUNDS', 'Devoluciones');
  static readonly FeesPaid = new AccountingMetric('FEES_PAID', 'Comisiones pagadas');
  static readonly ExpensesPaid = new AccountingMetric('EXPENSES_PAID', 'Egresos pagados');
  static readonly Contribution = new AccountingMetric('CONTRIBUTION', 'Contribución');
  static readonly Unknown = new AccountingMetric('UNKNOWN', 'Métrica no reconocida');
  static readonly financial = [this.Collected, this.Refunds, this.FeesPaid, this.ExpensesPaid] as const;
  static readonly all = [this.NetSales, ...this.financial, this.Contribution] as const;
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): AccountingMetric { return this.all.find(item => item.wire === raw) ?? this.Unknown; }
}

export class AccountingFormula {
  static readonly Contribution = new AccountingFormula('CONTRIBUTION', 'Contribución operativa');
  static readonly ReinvestmentSuggestion = new AccountingFormula('REINVESTMENT_SUGGESTION', 'Sugerencia de reinversión');
  static readonly OwnerSurplusEstimate = new AccountingFormula('OWNER_SURPLUS_ESTIMATE', 'Estimación de excedente');
  static readonly Unknown = new AccountingFormula('UNKNOWN', 'Fórmula desconocida');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): AccountingFormula {
    return [this.Contribution, this.ReinvestmentSuggestion, this.OwnerSurplusEstimate].find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class FormulaVersion {
  static readonly V1 = new FormulaVersion('V1', 'versión 1');
  static readonly Unknown = new FormulaVersion('UNKNOWN', 'versión desconocida');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): FormulaVersion { return raw === this.V1.wire ? this.V1 : this.Unknown; }
}

export class ReconciliationOutcome {
  static readonly Balanced = new ReconciliationOutcome('BALANCED', 'Balanceado');
  static readonly Shortage = new ReconciliationOutcome('SHORTAGE', 'Faltante');
  static readonly Overage = new ReconciliationOutcome('OVERAGE', 'Sobrante');
  static readonly Unavailable = new ReconciliationOutcome('UNAVAILABLE', 'Arqueo no disponible');
  static readonly Unknown = new ReconciliationOutcome('UNKNOWN', 'Resultado de arqueo desconocido');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): ReconciliationOutcome {
    return [this.Balanced, this.Shortage, this.Overage, this.Unavailable].find(item => item.wire === raw) ?? this.Unknown;
  }
}

export class ReportFailure {
  static readonly Validation = new ReportFailure(400, 'Revisá la caja, fecha, zona y filtros del período');
  static readonly Unauthenticated = new ReportFailure(401, 'Comprobá tu sesión para consultar reportes');
  static readonly Forbidden = new ReportFailure(403, 'Permiso insuficiente para consultar este período');
  static readonly NotVisible = new ReportFailure(404, 'Período o caja no disponible');
  static readonly Conflict = new ReportFailure(409, 'El período cambió. Consultalo nuevamente');
  static readonly Unavailable = new ReportFailure(503, 'Reporte temporalmente no disponible');
  static readonly Unknown = new ReportFailure(0, 'No se pudo comprobar el reporte');
  private constructor(readonly status: number, readonly label: string) {}
  static fromWire(status: unknown): ReportFailure {
    return [this.Validation, this.Unauthenticated, this.Forbidden, this.NotVisible, this.Conflict, this.Unavailable]
      .find(item => item.status === status) ?? this.Unknown;
  }
}

/** Validated query objects keep period identity and wire serialization in the domain edge. */
export class AccountingReportRequest {
  private constructor(readonly period: ReportPeriod, readonly params: Readonly<Record<string, string>>) {}
  static shift(sessionId: unknown): AccountingReportRequest | null {
    const id = this.id(sessionId);
    return id ? new AccountingReportRequest(ReportPeriod.Shift, Object.freeze({ cashSessionId: id })) : null;
  }
  static day(localDate: string, zone: string, filters: { terminalId?: unknown; cashierId?: unknown; cashSessionId?: unknown } = {}): AccountingReportRequest | null {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(localDate) || !Number.isFinite(Date.parse(`${localDate}T00:00:00Z`)) ||
        new Date(`${localDate}T00:00:00Z`).toISOString().slice(0, 10) !== localDate || !zone.includes('/')) return null;
    try { new Intl.DateTimeFormat('es', { timeZone: zone }); } catch { return null; }
    const params: Record<string, string> = { localDate, zone };
    for (const [key, value] of Object.entries(filters)) {
      if (value === '' || value === undefined || value === null) continue;
      const id = this.id(value); if (!id) return null; params[key] = id;
    }
    return new AccountingReportRequest(ReportPeriod.Day, Object.freeze(params));
  }
  private static id(value: unknown): string | null {
    if (typeof value !== 'string' && typeof value !== 'number') return null;
    const text = String(value);
    return /^[1-9]\d*$/.test(text) && Number.isSafeInteger(Number(text)) ? text : null;
  }
  matches(report: AccountingReport): boolean {
    return report.period === this.period && (this.period === ReportPeriod.Shift
      ? String(report.cashSessionId) === this.params['cashSessionId']
      : report.localDate === this.params['localDate'] && report.zone === this.params['zone']);
  }
}

export interface MetricCoverage { readonly state: DataCompleteness; readonly causes: readonly CompletenessCause[]; }
export interface CoveredValue { readonly metric: AccountingMetric; readonly value: TicketMoney | null; readonly coverage: MetricCoverage; }
export interface MethodTotals { readonly method: PaymentMethod; readonly values: readonly CoveredValue[]; readonly operatingCashFlow: TicketMoney | null; }
export interface Reconciliation {
  readonly expectedCash: TicketMoney | null; readonly declaredCash: TicketMoney | null; readonly difference: TicketMoney | null;
  readonly outcome: ReconciliationOutcome; readonly localWatermark: number | null;
}
export interface AccountingReport {
  readonly period: ReportPeriod; readonly cashSessionId: number | null; readonly localDate: string | null;
  readonly start: string; readonly endExclusive: string; readonly cutoff: string; readonly snapshot: string;
  readonly zone: string; readonly zoneVersion: string; readonly zoneEffectiveAt: string; readonly accountingVersion: number;
  readonly provisional: boolean; readonly grossSales: TicketMoney | null; readonly discounts: TicketMoney | null; readonly netSales: CoveredValue;
  readonly coverage: readonly CoveredValue[]; readonly totalsByMethod: readonly MethodTotals[];
  readonly formula: { readonly kind: AccountingFormula; readonly version: FormulaVersion; readonly value: TicketMoney | null; readonly coverage: MetricCoverage };
  readonly reconciliation: Reconciliation | null;
}
