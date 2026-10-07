import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
import { CashSessionComponent } from './cash-session.component';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { StaffRole, CashSessionStatus, CashMutationOutcome } from '../../core/domain/pos-types';
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
      provideHttpClient(), provideHttpClientTesting(), { provide: CounterContextService, useValue: { load: () => {}, invalidate: () => {} } },
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

  for (const operation of [
    { invoke: (component: CashSessionComponent) => component.close(), url: `${API_BASE}/cash-sessions/2/close` },
    { invoke: (component: CashSessionComponent) => component.addExpense(), url: `${API_BASE}/expenses` },
  ]) {
    it(`reloads authoritative state after conflict without replaying ${operation.url}`, async () => {
      const fixture = await create(StaffRole.Owner);
      const component = fixture.componentInstance;
      operation.invoke(component);
      operation.invoke(component);
      const request = http.expectOne(operation.url);
      request.flush(failure(CashMutationOutcome.Conflict), { status: 409, statusText: 'Conflict' });
      expect(component.session()).toBeNull(); expect(component.loading()).toBeTrue();
      expect(component.notice()).toBe(CashMutationOutcome.Conflict.label);
      component.close(); component.addExpense(); component.open();
      http.expectNone(request => request.method === 'POST');
      http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope([{ ...sessions[1], status: CashSessionStatus.Closed.wire }]));
      http.expectOne(`${API_BASE}/workspace`).flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'postgresql' }));
      expect(component.loading()).toBeFalse(); expect(component.session()).toBeNull();
      fixture.detectChanges(); expect(fixture.nativeElement.textContent).not.toContain('private details');
      http.expectNone(request => request.method === 'POST');
    });
    it(`clears selection for indistinguishable unavailable cash after ${operation.url}`, async () => {
      const fixture = await create(StaffRole.Owner);
      const component = fixture.componentInstance;
      operation.invoke(component);
      http.expectOne(operation.url).flush(failure(CashMutationOutcome.NotVisible), { status: 404, statusText: 'Not Found' });
      expect(component.session()).toBeNull(); expect(component.cashierId).toBeNull(); expect(component.visibleSessions()).toEqual([]);
      expect(component.error()).toBe(CashMutationOutcome.NotVisible.label);
      fixture.detectChanges(); expect(fixture.nativeElement.textContent).not.toContain('private details');
      http.expectNone(request => request.method === 'GET' || request.method === 'POST');
    });
  }
  it('uses a neutral failure and blocks mutation for unknown or lost expense responses', async () => {
    const fixture = await create(StaffRole.Owner); const component = fixture.componentInstance;
    component.addExpense();
    http.expectOne(`${API_BASE}/expenses`).flush({ ...failure(CashMutationOutcome.Conflict), errorCode: 'FUTURE_PRIVATE_FAILURE' }, { status: 409, statusText: 'Conflict' });
    expect(component.outcome()).toBe(CashMutationOutcome.Unknown); expect(component.error()).toBe(CashMutationOutcome.Unknown.label);
    component.addExpense(); component.close(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('FUTURE_PRIVATE_FAILURE');
    expect(fixture.nativeElement.textContent).not.toContain('private details');
    http.expectNone(request => request.method === 'POST' || request.method === 'GET');
  });
  it('reloads after a concurrent visible opening without replaying its POST', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.terminalId = 12; component.cashierId = 9; component.assignmentReason = 'Turno'; component.open();
    http.expectOne(`${API_BASE}/cash-sessions`).flush(failure(CashMutationOutcome.Conflict), { status: 409, statusText: 'Conflict' });
    expect(component.loading()).toBeTrue(); component.open();
    http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope([cash(3, 12, 9)]));
    http.expectOne(`${API_BASE}/workspace`).flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'postgresql' }));
    expect(component.session()?.id).toBe(3); expect(component.canOpen()).toBeFalse();
    http.expectNone(request => request.method === 'POST');
  });
  it('does not automatically replay an expense after a network failure', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.addExpense(); http.expectOne(`${API_BASE}/expenses`).error(new ProgressEvent('error'));
    expect(component.error()).toBe(CashMutationOutcome.Unknown.label);
    component.addExpense(); http.expectNone(request => request.method === 'POST' || request.method === 'GET');
  });
  it('clears the requested opening target when the blocking cash is not visible', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.terminalId = 12; component.cashierId = 9; component.assignmentReason = 'Turno'; component.open();
    http.expectOne(`${API_BASE}/cash-sessions`).flush(failure(CashMutationOutcome.NotVisible), { status: 404, statusText: 'Not Found' });
    expect(component.cashierId).toBeNull(); expect(component.visibleSessions()).toEqual([]);
    expect(component.error()).toBe(CashMutationOutcome.NotVisible.label); expect(component.canOpen()).toBeFalse();
    http.expectNone(request => request.method === 'POST' || request.method === 'GET');
  });
  it('does not claim expense success without a persisted result', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.addExpense(); http.expectOne(`${API_BASE}/expenses`).flush(envelope(null));
    expect(component.notice()).toBeNull(); expect(component.error()).toBe(CashMutationOutcome.Unknown.label);
    component.addExpense(); http.expectNone(request => request.method === 'POST');
  });
  it('discards mutation and reload responses admitted under an earlier identity', async () => {
    const component = (await create(StaffRole.Owner)).componentInstance;
    component.close(); const mutation = http.expectOne(`${API_BASE}/cash-sessions/2/close`);
    component.reload(); const boxes = http.expectOne(`${API_BASE}/cash-sessions`); const workspace = http.expectOne(`${API_BASE}/workspace`);
    const identity = TestBed.inject(SessionStore);
    identity.generation.update(value => value + 1); identity.changed.next(); authenticateTestSession(identity, StaffRole.Cashier);
    mutation.flush(envelope({ ...sessions[1], status: CashSessionStatus.Closed.wire }));
    boxes.flush(envelope(sessions)); workspace.flush(envelope({ terminalId: 11, cashierId: 8, persistence: 'postgresql' }));
    expect(component.session()).toBeNull(); expect(component.visibleSessions()).toEqual([]);
    expect(component.notice()).toBeNull(); expect(component.cashierId).toBeNull();
  });

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

function failure(outcome: CashMutationOutcome) {
  return { code: outcome.httpStatus, traceId: 'trace', data: null, errorCode: outcome.wire, message: 'private details', retryable: false };
}
