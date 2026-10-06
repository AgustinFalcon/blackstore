import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { StaffRole } from '../domain/pos-types';
import { StaffPermission } from '../domain/session-types';
import { sessionGuard } from './session.guard';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';

describe('sessionGuard', () => {
  let session: SessionStore;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient()] });
    session = TestBed.inject(SessionStore);
  });
  async function guard(permission: StaffPermission): Promise<boolean | UrlTree> {
    const route = new ActivatedRouteSnapshot(); route.data = { permission };
    return await TestBed.runInInjectionContext(() => sessionGuard(route, {} as RouterStateSnapshot)) as boolean | UrlTree;
  }
  it('waits for bootstrap before granting a deep link', async () => {
    let release!: () => void;
    spyOn(session, 'bootstrap').and.returnValue(new Promise<void>(resolve => release = resolve));
    let done = false;
    const pending = guard(StaffPermission.SaleReserve).then(result => { done = true; return result; });
    await Promise.resolve(); expect(done).toBeFalse();
    authenticateTestSession(session); release();
    expect(await pending).toBeTrue();
  });
  it('redirects anonymous deep links and prevents cashier reports', async () => {
    spyOn(session, 'bootstrap').and.resolveTo();
    expect(String(await guard(StaffPermission.CatalogRead))).toBe('/sesion');
    authenticateTestSession(session);
    expect(String(await guard(StaffPermission.ShiftReportRead))).toBe('/');
    expect(session.notice()).toContain('Permiso insuficiente');
  });
  it('allows auditor reports but denies catalogue and unknown permissions', async () => {
    spyOn(session, 'bootstrap').and.resolveTo(); authenticateTestSession(session, StaffRole.Auditor);
    expect(await guard(StaffPermission.ShiftReportRead)).toBeTrue();
    expect(String(await guard(StaffPermission.CatalogRead))).toBe('/reportes');
    expect(String(await guard(StaffPermission.Unknown))).toBe('/reportes');
  });
});
