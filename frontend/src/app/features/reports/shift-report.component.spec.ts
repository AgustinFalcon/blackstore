import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
import { CashSessionStatus, StaffRole } from '../../core/domain/pos-types';
import { ReconciliationOutcome } from '../../core/domain/accounting-report';
import { reportEnvelope, reportFixture } from '../../core/infrastructure/accounting-report-test-helper';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { ShiftReportComponent } from './shift-report.component';

describe('ShiftReportComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ShiftReportComponent], providers: [provideHttpClient(), provideHttpClientTesting()] }));
  function setup() {
    const identity = TestBed.inject(SessionStore); authenticateTestSession(identity, StaffRole.Owner);
    const fixture = TestBed.createComponent(ShiftReportComponent); fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(`${API_BASE}/cash-sessions`).flush(reportEnvelope([{ id: 12, terminalId: 2, cashierId: 7, status: CashSessionStatus.Closed.wire, openingCash: 0 }]));
    fixture.detectChanges(); return { identity, fixture, http, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement };
  }
  it('requires an explicit visible session and has labelled inputs, fieldset and status/alert regions', () => {
    const { fixture, http, component, root } = setup();
    expect(root.querySelectorAll('label input').length).toBe(5);
    expect(root.querySelector('label select')).not.toBeNull(); expect(root.querySelector('fieldset legend')).not.toBeNull();
    component.selectShift(12); component.loadShift(); fixture.detectChanges();
    expect(root.querySelector('[role="status"]')?.textContent).toContain('Consultando turno');
    expect(root.querySelector('[aria-labelledby="shift-heading"]')?.getAttribute('aria-busy')).toBe('true');
    http.expectOne(request => request.url.endsWith('/reports/shift')).flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges(); expect(root.querySelector('[role="alert"]')?.textContent).toContain('Turno');
    http.verify(); fixture.destroy();
  });
  it('renders every reconciliation outcome, cutoff and nullable evidence without wire labels', () => {
    const { fixture, http, component, root } = setup(); component.selectShift(12);
    for (const [outcome, declaration, difference] of [[ReconciliationOutcome.Balanced, 65, 0], [ReconciliationOutcome.Shortage, 60, -5],
      [ReconciliationOutcome.Overage, 70, 5], [ReconciliationOutcome.Unavailable, 65, null]] as const) {
      component.loadShift(); const raw = reportFixture();
      Object.assign(raw.reconciliation!, { outcome: outcome.wire, declaredCash: declaration, difference, expectedCash: difference === null ? null : 65 });
      http.expectOne(request => request.url.endsWith('/reports/shift')).flush(reportEnvelope(raw)); fixture.detectChanges();
      expect(root.textContent).toContain(outcome.label); expect(root.textContent).toContain(raw.cutoff);
      expect(root.textContent).toContain('Snapshot consistente'); expect(root.textContent).toContain('no equivale a cero');
      if (difference === null) expect(root.textContent).toContain('No disponible');
    }
    http.verify(); fixture.destroy();
  });
  it('immediately cancels and clears the selected period when changed, and clears filters on identity change', () => {
    const { fixture, http, component, identity } = setup();
    component.selectShift(12); component.loadShift(); const old = http.expectOne(request => request.url.endsWith('/reports/shift'));
    component.selectShift(null); expect(old.cancelled).toBeTrue(); expect(component.store.shift.report()).toBeNull();
    component.zone = 'America/Argentina/Buenos_Aires'; component.localDate = '2026-10-07'; component.cashierId = 7;
    identity.generation.update(value => value + 1); identity.changed.next(); fixture.detectChanges();
    expect(component.sessions()).toEqual([]); expect(component.zone).toBe(''); expect(component.cashierId).toBeNull();
    http.verify(); fixture.destroy();
  });
});
