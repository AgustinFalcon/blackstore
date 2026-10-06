import { StaffRole } from './pos-types';
import { SessionState, StaffPermission, permits } from './session-types';
import { SessionWireMapper } from '../infrastructure/session-wire-mapper';

describe('staff closed domain', () => {
  it('maps unknown roles, permissions and states to closed denying cases', () => {
    expect(StaffRole.fromWire('future')).toBe(StaffRole.Unknown);
    expect(StaffPermission.fromWire('future')).toBe(StaffPermission.Unknown);
    expect(SessionState.fromWire('future')).toBe(SessionState.Unknown);
    for (const role of [StaffRole.Cashier, StaffRole.Supervisor, StaffRole.Owner, StaffRole.Auditor, StaffRole.Unknown]) {
      expect(permits(role, StaffPermission.Unknown)).toBeFalse();
    }
    for (const permission of Object.values(StaffPermission)) {
      if (permission instanceof StaffPermission) expect(permits(StaffRole.Unknown, permission)).toBeFalse();
    }
  });
  it('grants auditor only explicit reads and prevents cashier reports', () => {
    expect(permits(StaffRole.Auditor, StaffPermission.CashSessionList)).toBeTrue();
    expect(permits(StaffRole.Auditor, StaffPermission.DailyReportRead)).toBeTrue();
    for (const permission of [StaffPermission.WorkspaceRead, StaffPermission.CatalogRead, StaffPermission.SaleRead, StaffPermission.PaymentCapture, StaffPermission.CashSessionOpen]) expect(permits(StaffRole.Auditor, permission)).toBeFalse();
    expect(permits(StaffRole.Cashier, StaffPermission.ShiftReportRead)).toBeFalse();
    expect(permits(StaffRole.Owner, StaffPermission.PaymentReverse)).toBeTrue();
  });
  it('rejects malformed identity instead of displaying raw role', () => {
    expect(SessionWireMapper.staff({ id: 7, displayName: 'Staff', role: 'future' })).toBeNull();
    expect(SessionWireMapper.staff({ id: 0, displayName: 'Staff', role: StaffRole.Cashier.wire })).toBeNull();
    expect(SessionWireMapper.staff({ id: 7, displayName: 'Staff', role: StaffRole.Cashier.wire })?.role).toBe(StaffRole.Cashier);
  });
});
