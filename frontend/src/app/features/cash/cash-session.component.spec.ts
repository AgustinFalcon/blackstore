import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AccountingCoverage, CommandFailure, CommandOutcome, ExpenseOperation } from '../../core/domain/accounting-command';
import { ReconciliationOutcome } from '../../core/domain/accounting-report';
import { ACCOUNTING_API_BASE, API_BASE } from '../../core/api';
import { CashSessionComponent } from './cash-session.component';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { StaffRole, CashSessionStatus, CashMutationOutcome, PaymentMethod } from '../../core/domain/pos-types';
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

describe('CashSessionComponent v2 commands', () => {
  let http: HttpTestingController;
  const cash = { id: 2, terminalId: 10, cashierId: 7, status: CashSessionStatus.Open.wire, openingCash: 0 };
  async function create(role = StaffRole.Owner) {
    await TestBed.configureTestingModule({ imports: [CashSessionComponent], providers: [
      provideHttpClient(), provideHttpClientTesting(), { provide: CounterContextService, useValue: { load: () => {}, invalidate: () => {} } },
    ] }).compileComponents();
    authenticateTestSession(TestBed.inject(SessionStore), role);
    const fixture = TestBed.createComponent(CashSessionComponent);
    http = TestBed.inject(HttpTestingController);
    reload([cash]); fixture.detectChanges(); return fixture;
  }
  function reload(cashSessions: unknown[]) {
    http.match(`${API_BASE}/workspace`).forEach(request => request.flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'postgresql' })));
    http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope(cashSessions));
  }
  const receipt = (commandId: string, overrides = {}) => envelope({
    outcome: CommandOutcome.Committed.wire, commandId, cashSessionId: 2, ledgerEventIds: [1], committedAt: '2026-10-07T12:00:00Z', failure: null,
    paymentId: null, expenseId: null, settlementId: null, closeSnapshot: null, ...overrides,
  });
  afterEach(() => http.verify());
  it('blocks occupied workstation and foreign cashier assignment without replacing a box', async () => {
    const component = (await create()).componentInstance;
    component.assignmentReason = 'Turno'; component.terminalId = 10; component.cashierId = 9; component.open();
    expect(component.canOpen()).toBeFalse();
    component.terminalId = 12; component.cashierId = 7; component.open(); expect(component.canOpen()).toBeFalse();
    component.cashierId = 9; component.assignmentReason = ''; component.open(); expect(component.canOpen()).toBeFalse();
    http.expectNone(request => request.method === 'POST');
  });
  it('cashier cannot select or open another cashier box', async () => {
    const component = (await create(StaffRole.Cashier)).componentInstance;
    component.terminalId = 12; component.cashierId = 9; component.assignmentReason = 'Ajeno';
    component.open(); component.close(); component.addExpense();
    expect(component.session()).toBeNull(); http.expectNone(request => request.method === 'POST');
  });
  it('ignores pending workspace responses after identity change', async () => {
    const component = (await create()).componentInstance; component.reload();
    const boxes = http.expectOne(`${API_BASE}/cash-sessions`);
    const workspace = http.expectOne(`${API_BASE}/workspace`);
    const identity = TestBed.inject(SessionStore);
    identity.generation.update(value => value + 1); identity.changed.next();
    boxes.flush(envelope([cash])); workspace.flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'postgresql' }));
    expect(component.session()).toBeNull(); expect(component.visibleSessions()).toEqual([]);
  });
  it('opens the requested workstation through v2 and refreshes authoritative read data', async () => {
    const component = (await create()).componentInstance;
    component.terminalId = 12; component.cashierId = 9; component.assignmentReason = 'Turno'; component.open(); component.open();
    const post = http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions`);
    expect(post.request.body.commandId).toMatch(/^[0-9a-f-]{36}$/);
    expect(post.request.body.terminalId).toBe(12);
    post.flush(receipt(post.request.body.commandId));
    reload([{ ...cash, id: 3, terminalId: 12, cashierId: 9 }]);
    expect(component.session()?.id).toBe(3);
    http.expectNone(request => request.method === 'POST');
  });
  it('sends paid expense using the closed operation and v2 payment method fields', async () => {
    const component = (await create()).componentInstance; component.addExpense();
    const post = http.expectOne(`${ACCOUNTING_API_BASE}/expenses`);
    expect(post.request.body.operation).toBe(ExpenseOperation.AccrueAndSettle.wire);
    expect(post.request.body.paymentMethod).toBe(PaymentMethod.Cash.wire); expect(post.request.body.method).toBeUndefined();
    post.flush(receipt(post.request.body.commandId, { expenseId: 11, settlementId: 12 })); reload([cash]);
  });
  it('recovers lost close response by GET with exactly the same commandId and renders its snapshot', async () => {
    const fixture = await create(); const component = fixture.componentInstance;
    component.declared = 90; component.close(); const post = http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions/2/close`);
    const id = post.request.body.commandId;
    expect(post.request.body.cashSessionId).toBe(2); expect(post.request.body.declaredCash).toBe(90);
    post.flush(null, { status: 503, statusText: 'Lost' });
    component.close(); http.expectNone(request => request.method === 'POST');
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${id}`).flush(receipt(id, {
      closeSnapshot: { declaredCash: '90', expectedCash: '100', difference: '-10', outcome: ReconciliationOutcome.Shortage.wire, coverage: AccountingCoverage.Complete.wire },
    }));
    reload([{ ...cash, status: CashSessionStatus.Closed.wire }]); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Faltante');
    expect(fixture.nativeElement.textContent).toContain('-10.00');
  });
  it('keeps NotFound uncertain and manual consultation only reads, without a new command', async () => {
    const component = (await create()).componentInstance; component.addExpense();
    const post = http.expectOne(`${ACCOUNTING_API_BASE}/expenses`); const id = post.request.body.commandId;
    post.flush('unrecognized');
    const missing = { code: 404, traceId: 'trace', data: { outcome: CommandOutcome.NotFound.wire, commandId: null, failure: null }, errorCode: 'NOT_FOUND' };
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${id}`).flush(missing, { status: 404, statusText: 'Missing' });
    component.addExpense(); component.close(); expect(component.canMutate()).toBeFalse();
    component.consultReceipt();
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${id}`).flush(missing, { status: 404, statusText: 'Missing' });
    expect(component.commands.unresolved()?.commandId).toBe(id); http.expectNone(request => request.method === 'POST');
  });
  it('fails closed for PAUSED even after read-only refresh', async () => {
    const component = (await create()).componentInstance; component.close();
    http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions/2/close`).flush({
      code: 409, traceId: 'trace', data: { outcome: CommandOutcome.Unknown.wire, commandId: null, failure: CommandFailure.Paused.wire },
    }, { status: 409, statusText: 'Paused' });
    component.reload(); reload([cash]); component.addExpense();
    expect(component.canMutate()).toBeFalse(); http.expectNone(request => request.method === 'POST');
  });
  it('auditor can read without rendering mutation actions', async () => {
    const fixture = await create(StaffRole.Auditor); fixture.componentInstance.close(); fixture.componentInstance.addExpense();
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull(); http.expectNone(request => request.method === 'POST');
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
