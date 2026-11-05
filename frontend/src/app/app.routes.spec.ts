import { StaffPermission } from './core/domain/session-types';
import { CashPageMode } from './core/domain/cash-page-mode';
import { CashSessionComponent } from './features/cash/cash-session.component';
import { SaleTicketSnapshotComponent } from './features/sales/sale-ticket-snapshot.component';
import { routes } from './app.routes';

describe('operational routes', () => {
  it('exposes a guarded dedicated cash-close route with a closed page mode', () => {
    const route = routes.find(candidate => candidate.path === 'caja/cierre');
    expect(route?.component).toBe(CashSessionComponent);
    expect(route?.data?.['permission']).toBe(StaffPermission.CashSessionClose);
    expect(CashPageMode.fromWire(route?.data?.['cashPageMode'])).toBe(CashPageMode.Close);
    expect(route?.canActivate?.length).toBe(1);
  });

  it('exposes a guarded read-only sale snapshot route', () => {
    const route = routes.find(candidate => candidate.path === 'ticket/:saleId');
    expect(route?.component).toBe(SaleTicketSnapshotComponent);
    expect(route?.data?.['permission']).toBe(StaffPermission.SaleRead);
    expect(route?.canActivate?.length).toBe(1);
  });
});
