import { StaffRole } from '../domain/pos-types';
import { StaffIdentity } from '../domain/session-types';

export class SessionWireMapper {
  static staff(raw: unknown): StaffIdentity | null {
    if (!raw || typeof raw !== 'object') return null;
    const value = raw as Record<string, unknown>;
    const role = StaffRole.fromWire(value['role']);
    if (!Number.isSafeInteger(value['id']) || Number(value['id']) <= 0 || typeof value['displayName'] !== 'string' || !value['displayName'].trim() || role === StaffRole.Unknown) return null;
    return Object.freeze({ id: Number(value['id']), displayName: value['displayName'], role });
  }
  static csrf(raw: unknown): string | null {
    if (!raw || typeof raw !== 'object') return null;
    const token = (raw as Record<string, unknown>)['csrfToken'];
    return typeof token === 'string' && token.length > 0 ? token : null;
  }
}
