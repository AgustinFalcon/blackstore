import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
import { CashSessionComponent } from './cash-session.component';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { StaffRole, CashSessionStatus } from '../../core/domain/pos-types';
import { CounterContextService } from '../../core/services/counter-context.service';

describe('CashSessionComponent', () => {
  it('fails closed when the backend returns an unknown session status', async () => {
    await TestBed.configureTestingModule({
      imports: [CashSessionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    authenticateTestSession(TestBed.inject(SessionStore));
    const fixture = TestBed.createComponent(CashSessionComponent);
    const http = TestBed.inject(HttpTestingController);
    const unknownSession = {
      id: 5,
      terminalId: 10,
      cashierId: 7,
      status: 'FUTURE_CASH_STATE',
      openingCash: 100,
    };

    http.match(`${API_BASE}/workspace`).forEach((request) => request.flush(envelope({
      terminalId: 10,
      cashierId: 7,
      persistence: 'memory',
    })));
    http.match(`${API_BASE}/cash-sessions`).forEach((request) => request.flush(envelope([unknownSession])));
    http.expectOne(`${API_BASE}/catalog`).flush(envelope({
      version: 'catalog-v1',
      stale: false,
      importedAt: null,
      validUntil: null,
      items: [],
    }));
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    const openButton = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(text).toContain('estado desconocido');
    expect(text).not.toContain('FUTURE_CASH_STATE');
    expect(openButton.disabled).toBeTrue();

    fixture.componentInstance.close();
    fixture.componentInstance.addExpense();
    fixture.componentInstance.open();
    http.expectNone((request) => request.method === 'POST');
    http.verify();
  });

  it('does not let a historical reconciliation state block a new session', async () => {
    await TestBed.configureTestingModule({
      imports: [CashSessionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    authenticateTestSession(TestBed.inject(SessionStore));
    const fixture = TestBed.createComponent(CashSessionComponent);
    const http = TestBed.inject(HttpTestingController);
    flushInitialRequests(http, 'RECONCILIATION_REQUIRED');
    fixture.detectChanges();

    const openButton = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(fixture.componentInstance.session()).toBeNull();
    expect(openButton.disabled).toBeFalse();
    http.verify();
  });
});

describe('CashSessionComponent explicit workstation selection', () => {
  let http: HttpTestingController;
  const cash = (id: number, terminalId: number, cashierId: number) => ({ id, terminalId, cashierId, status: CashSessionStatus.Open.wire, openingCash: 0 });
  const sessions = [cash(1, 11, 8), cash(2, 10, 7)];

  async function create(role: StaffRole) {
    await TestBed.configureTestingModule({ imports: [CashSessionComponent], providers: [
      provideHttpClient(), provideHttpClientTesting(), { provide: CounterContextService, useValue: { load: () => {} } },
    ] }).compileComponents();
    authenticateTestSession(TestBed.inject(SessionStore), role);
    const fixture = TestBed.createComponent(CashSessionComponent);
    http = TestBed.inject(HttpTestingController);
    http.match(`${API_BASE}/workspace`).forEach(request => request.flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'memory' })));
    http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope(sessions));
    fixture.detectChanges();
    return fixture;
  }
  afterEach(() => http.verify());

  for (const role of [StaffRole.Supervisor, StaffRole.Owner]) {
    it(`opens an unoccupied requested terminal and cashier for ${role.label}`, async () => {
      const fixture = await create(role);
      const component = fixture.componentInstance;
      expect(component.session()?.id).toBe(2);
      component.terminalId = 12; component.cashierId = 9; component.assignmentReason = 'Asignación de turno';
      fixture.detectChanges();
      expect(component.session()).toBeNull(); expect(component.canOpen()).toBeTrue();
      expect((fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBeFalse();
      component.open();
      const request = http.expectOne(`${API_BASE}/cash-sessions`);
      expect(request.request.body.terminalId).toBe(12); expect(request.request.body.cashierId).toBe(9);
      request.flush(envelope(cash(3, 12, 9)));
      expect(component.session()?.id).toBe(3);
    });
    it(`closes only the explicitly selected visible cash session for ${role.label}`, async () => {
      const component = (await create(role)).componentInstance;
      component.selectSession(1); component.closeReason = 'Cierre supervisado';
      expect(component.terminalId).toBe(11); expect(component.cashierId).toBe(8); expect(component.session()?.id).toBe(1);
      component.close();
      http.expectNone(`${API_BASE}/cash-sessions/2/close`);
      const request = http.expectOne(`${API_BASE}/cash-sessions/1/close`);
      request.flush(envelope({ ...sessions[0], status: CashSessionStatus.Closed.wire }));
      expect(component.session()).toBeNull();
    });
  }
  it('blocks occupied terminals, occupied cashiers and missing reason instead of replacing another box', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.assignmentReason = 'Asignación'; component.terminalId = 11; component.cashierId = 9;
    component.open(); expect(component.canOpen()).toBeFalse();
    component.terminalId = 12; component.cashierId = 8;
    component.open(); expect(component.canOpen()).toBeFalse();
    component.cashierId = 9; component.assignmentReason = '';
    component.open(); expect(component.canOpen()).toBeFalse();
    http.expectNone(request => request.method === 'POST');
  });
  it('does not allow cashier to choose another cashier even if the response includes that box', async () => {
    const component = (await create(StaffRole.Cashier)).componentInstance;
    expect(component.session()?.id).toBe(2); expect(component.selectableSessions().length).toBe(1);
    component.selectSession(1); component.close(); component.addExpense();
    expect(component.session()).toBeNull();
    component.terminalId = 12; component.cashierId = 9; component.assignmentReason = 'Intento ajeno'; component.open();
    http.expectNone(request => request.method === 'POST');
  });
  it('lets auditor select a visible box without any mutation', async () => {
    const fixture = await create(StaffRole.Auditor);
    const component = fixture.componentInstance; component.selectSession(1);
    expect(component.session()?.id).toBe(1);
    component.close(); component.addExpense(); component.open(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
    http.expectNone(request => request.method === 'POST');
  });
  it('preserves an explicit target during reload and reversed response order', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.selectSession(1); component.reload();
    http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope([...sessions].reverse()));
    expect(component.loading()).toBeTrue();
    http.expectOne(`${API_BASE}/workspace`).flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'memory' }));
    expect(component.loading()).toBeFalse(); expect(component.terminalId).toBe(11); expect(component.session()?.id).toBe(1);
  });
});

function flushInitialRequests(http: HttpTestingController, status: string): void {
  http.match(`${API_BASE}/workspace`).forEach((request) => request.flush(envelope({
    terminalId: 10,
    cashierId: 7,
    persistence: 'memory',
  })));
  http.match(`${API_BASE}/cash-sessions`).forEach((request) => request.flush(envelope([{
    id: 5,
    terminalId: 10,
    cashierId: 7,
    status,
    openingCash: 100,
  }])));
  http.expectOne(`${API_BASE}/catalog`).flush(envelope({
    version: 'catalog-v1',
    stale: false,
    importedAt: null,
    validUntil: null,
    items: [],
  }));
}

function envelope<T>(data: T) {
  return { code: 200, traceId: 'test', data, message: null, errorCode: null, retryable: null };
}
