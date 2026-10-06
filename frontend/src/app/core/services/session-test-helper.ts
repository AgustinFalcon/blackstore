import { StaffRole } from '../domain/pos-types';
import { SessionState } from '../domain/session-types';
import { SessionStore } from './session.store';
export function authenticateTestSession(session: SessionStore, role = StaffRole.Cashier): void {
  session.staff.set({ id: 7, displayName: 'Operador de prueba', role });
  session.state.set(SessionState.Authenticated);
}
