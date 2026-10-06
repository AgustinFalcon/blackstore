import { CashSessionData } from '../models/pos-models';
import { StaffIdentity, StaffPermission, permits } from './session-types';

export class CashSessionSelectionState {
  static readonly Empty = new CashSessionSelectionState('Sin caja activa para la terminal y el cajero solicitados', false);
  static readonly Selected = new CashSessionSelectionState('Caja seleccionada', true);
  static readonly Ambiguous = new CashSessionSelectionState('Hay varias cajas activas asociadas a la selección. No se puede operar', false);
  static readonly Unknown = new CashSessionSelectionState('No se pudo comprobar la caja seleccionada', false);
  private constructor(readonly label: string, readonly permitsMutation: boolean) {}
}

/** Resolves one requested workstation; never substitutes another visible cash session. */
export class CashSessionSelection {
  private constructor(readonly state: CashSessionSelectionState, readonly session: CashSessionData | null) {}

  static visible(sessions: readonly CashSessionData[], staff: StaffIdentity | null): readonly CashSessionData[] {
    if (!staff || !permits(staff.role, StaffPermission.CashSessionList)) return [];
    return sessions.filter(item => staff.role.canAssignCashier || permits(staff.role, StaffPermission.ShiftReportRead) || item.cashierId === staff.id);
  }

  static requested(sessions: readonly CashSessionData[], staff: StaffIdentity | null, terminalId: number, cashierId: number | null): CashSessionSelection {
    if (!staff || !Number.isSafeInteger(terminalId) || terminalId <= 0 || !Number.isSafeInteger(cashierId) || Number(cashierId) <= 0) {
      return new CashSessionSelection(CashSessionSelectionState.Unknown, null);
    }
    const matches = this.visible(sessions, staff).filter(item => item.terminalId === terminalId && item.cashierId === cashierId && !item.status.canStartNewSession);
    if (matches.length > 1) return new CashSessionSelection(CashSessionSelectionState.Ambiguous, null);
    if (matches.length === 0) return new CashSessionSelection(CashSessionSelectionState.Empty, null);
    const session = matches[0];
    return new CashSessionSelection(session.status.isOpen ? CashSessionSelectionState.Selected : CashSessionSelectionState.Unknown, session);
  }

  static canOpen(sessions: readonly CashSessionData[], staff: StaffIdentity | null, terminalId: number, cashierId: number | null): boolean {
    if (!staff || !permits(staff.role, StaffPermission.CashSessionOpen) || !Number.isSafeInteger(terminalId) || terminalId <= 0 || !Number.isSafeInteger(cashierId) || Number(cashierId) <= 0) return false;
    if (cashierId !== staff.id && !staff.role.canAssignCashier) return false;
    return !this.visible(sessions, staff).some(item => !item.status.canStartNewSession && (item.terminalId === terminalId || item.cashierId === cashierId));
  }
}
