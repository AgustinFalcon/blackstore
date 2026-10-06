import { StaffRole } from './pos-types';

export class StaffPermission {
  static readonly PublicHealthRead = new StaffPermission('PublicHealthRead');
  static readonly CsrfBootstrap = new StaffPermission('CsrfBootstrap');
  static readonly StaffLogin = new StaffPermission('StaffLogin');
  static readonly SessionRead = new StaffPermission('SessionRead');
  static readonly StaffLogout = new StaffPermission('StaffLogout');
  static readonly CatalogRead = new StaffPermission('CatalogRead');
  static readonly WorkspaceRead = new StaffPermission('WorkspaceRead');
  static readonly CashSessionList = new StaffPermission('CashSessionList');
  static readonly CashSessionOpen = new StaffPermission('CashSessionOpen');
  static readonly CashSessionClose = new StaffPermission('CashSessionClose');
  static readonly SaleReserve = new StaffPermission('SaleReserve');
  static readonly SaleRead = new StaffPermission('SaleRead');
  static readonly SaleCommit = new StaffPermission('SaleCommit');
  static readonly SaleRelease = new StaffPermission('SaleRelease');
  static readonly PaymentCapture = new StaffPermission('PaymentCapture');
  static readonly PaymentReverse = new StaffPermission('PaymentReverse');
  static readonly ExpenseRecord = new StaffPermission('ExpenseRecord');
  static readonly ShiftReportRead = new StaffPermission('ShiftReportRead');
  static readonly DailyReportRead = new StaffPermission('DailyReportRead');
  static readonly Unknown = new StaffPermission('Unknown');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): StaffPermission {
    return Object.values(StaffPermission).find(value => value instanceof StaffPermission && value.wire === raw) ?? StaffPermission.Unknown;
  }
}

export class SessionState {
  static readonly Loading = new SessionState('LOADING', 'Comprobando sesión', false);
  static readonly Authenticated = new SessionState('AUTHENTICATED', 'Sesión vigente', true);
  static readonly Anonymous = new SessionState('ANONYMOUS', 'Ingresá con tu cuenta BlackStore', false);
  static readonly Expired = new SessionState('EXPIRED', 'La sesión venció. Ingresá nuevamente', false);
  static readonly Unknown = new SessionState('UNKNOWN', 'No se pudo comprobar la sesión', false);
  static readonly Unavailable = new SessionState('UNAVAILABLE', 'Servicio de sesión no disponible', false);
  private constructor(readonly wire: string, readonly label: string, readonly isAuthenticated: boolean) {}
  static fromWire(raw: unknown): SessionState {
    return Object.values(SessionState).find(value => value instanceof SessionState && value.wire === raw) ?? SessionState.Unknown;
  }
}

export interface StaffIdentity { readonly id: number; readonly displayName: string; readonly role: StaffRole; }

const operations = [StaffPermission.CatalogRead, StaffPermission.WorkspaceRead, StaffPermission.CashSessionList,
  StaffPermission.CashSessionOpen, StaffPermission.CashSessionClose, StaffPermission.SaleReserve, StaffPermission.SaleRead,
  StaffPermission.SaleCommit, StaffPermission.SaleRelease, StaffPermission.PaymentCapture, StaffPermission.PaymentReverse, StaffPermission.ExpenseRecord];
const reports = [StaffPermission.ShiftReportRead, StaffPermission.DailyReportRead];
export function permits(role: StaffRole, permission: StaffPermission): boolean {
  if (role === StaffRole.Unknown || permission === StaffPermission.Unknown) return false;
  const common = [StaffPermission.SessionRead, StaffPermission.StaffLogout, StaffPermission.CsrfBootstrap];
  if (role === StaffRole.Cashier) return [...common, ...operations].includes(permission);
  if (role === StaffRole.Supervisor || role === StaffRole.Owner) return [...common, ...operations, ...reports].includes(permission);
  if (role === StaffRole.Auditor) return [...common, StaffPermission.CashSessionList, ...reports].includes(permission);
  return false;
}
