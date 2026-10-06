import { CashSessionSelection, CashSessionSelectionState } from './cash-session-selection';
import { CashSessionStatus, StaffRole } from './pos-types';
import { StaffIdentity } from './session-types';
import { CashSessionData } from '../models/pos-models';

describe('CashSessionSelection', () => {
  const staff = (role: StaffRole): StaffIdentity => ({ id: 7, displayName: 'Staff', role });
  const cash = (id: number, terminalId: number, cashierId: number, status = CashSessionStatus.Open): CashSessionData => ({ id, terminalId, cashierId, status, openingCash: 0 });
  const sessions = [cash(1, 11, 8), cash(2, 10, 7)];

  it('resolves only the requested terminal and cashier, regardless of response order', () => {
    expect(CashSessionSelection.requested(sessions, staff(StaffRole.Owner), 10, 7).session?.id).toBe(2);
    expect(CashSessionSelection.requested(sessions, staff(StaffRole.Owner), 12, 9).state).toBe(CashSessionSelectionState.Empty);
    expect(CashSessionSelection.requested(sessions, staff(StaffRole.Owner), 12, 9).session).toBeNull();
  });
  it('permits supervisor/owner to open another valid terminal, blocking occupied terminal or cashier', () => {
    for (const role of [StaffRole.Supervisor, StaffRole.Owner]) {
      expect(CashSessionSelection.canOpen(sessions, staff(role), 12, 9)).toBeTrue();
      expect(CashSessionSelection.canOpen(sessions, staff(role), 11, 9)).toBeFalse();
      expect(CashSessionSelection.canOpen(sessions, staff(role), 12, 8)).toBeFalse();
    }
  });
  it('denies foreign cashier selection and all auditor/unknown mutations', () => {
    expect(CashSessionSelection.requested(sessions, staff(StaffRole.Cashier), 11, 8).session).toBeNull();
    expect(CashSessionSelection.canOpen([], staff(StaffRole.Cashier), 12, 9)).toBeFalse();
    expect(CashSessionSelection.canOpen([], staff(StaffRole.Cashier), 12, 7)).toBeTrue();
    for (const role of [StaffRole.Auditor, StaffRole.Unknown]) expect(CashSessionSelection.canOpen([], staff(role), 12, 7)).toBeFalse();
    expect(CashSessionSelection.visible(sessions, staff(StaffRole.Auditor)).length).toBe(2);
    expect(CashSessionSelection.visible(sessions, staff(StaffRole.Unknown)).length).toBe(0);
  });
  it('fails closed for unknown or ambiguous active sessions and invalid target IDs', () => {
    const ambiguous = [...sessions, cash(3, 10, 7)];
    expect(CashSessionSelection.requested(ambiguous, staff(StaffRole.Owner), 10, 7).state).toBe(CashSessionSelectionState.Ambiguous);
    expect(CashSessionSelection.requested(ambiguous, staff(StaffRole.Owner), 10, 7).session).toBeNull();
    const unknown = [cash(1, 10, 7, CashSessionStatus.Unknown)];
    expect(CashSessionSelection.requested(unknown, staff(StaffRole.Owner), 10, 7).state).toBe(CashSessionSelectionState.Unknown);
    expect(CashSessionSelection.canOpen(unknown, staff(StaffRole.Owner), 10, 7)).toBeFalse();
    for (const terminal of [0, -1, 1.5, NaN]) expect(CashSessionSelection.canOpen([], staff(StaffRole.Owner), terminal, 7)).toBeFalse();
    expect(CashSessionSelection.canOpen([], staff(StaffRole.Owner), 10, null)).toBeFalse();
  });
  it('allows opening after historical closed or reconciliation sessions', () => {
    for (const status of [CashSessionStatus.Closed, CashSessionStatus.ReconciliationRequired]) {
      const history = [cash(1, 10, 7, status)];
      expect(CashSessionSelection.requested(history, staff(StaffRole.Owner), 10, 7).session).toBeNull();
      expect(CashSessionSelection.canOpen(history, staff(StaffRole.Owner), 10, 7)).toBeTrue();
    }
  });
});
