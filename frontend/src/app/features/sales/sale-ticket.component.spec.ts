import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { AccountingCommand, AccountingCommandKind, AccountingCoverage, CommandFailure, CommandOutcome, ExpenseOperation } from '../../core/domain/accounting-command';
import { commandEnvelope } from '../../core/infrastructure/accounting-command-test-helper';
import { ReconciliationOutcome } from '../../core/domain/accounting-report';
import { ACCOUNTING_API_BASE, API_BASE } from '../../core/api';
import { PaymentStatus, SaleAction, SaleStatus } from '../../core/domain/pos-types';
import { PaymentCoverage, TicketIdentity } from '../../core/domain/ticket-transition';
import { CounterContextService } from '../../core/services/counter-context.service';
import { SaleTicketComponent } from './sale-ticket.component';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';

describe('SaleTicketComponent guarded writes (T05/T11)', () => {
  let component: SaleTicketComponent;
  let http: HttpTestingController;
  const envelope = (data: unknown) => ({ code: 200, errorCode: null, traceId: 'trace', message: null, retryable: null, data });
  const snapshot = (identity: TicketIdentity, coverage: PaymentCoverage, pending: string, status = SaleStatus.Reserved) => envelope({
    ...identity, status: status.wire, receipt: 'receipt', reservationRef: 'ref', evidenceValid: true, totalAmount: '18',
    pendingAmount: pending, paymentCoverage: coverage.wire, hasPaymentHistory: coverage !== PaymentCoverage.Unpaid,
    retired: false, blocked: false,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SaleTicketComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), {
        provide: CounterContextService,
        useValue: { load: () => {}, catalog: signal(null), openSession: signal({ id: 1 }), cashierId: signal(7), blockReason: () => null },
      }],
    });
    authenticateTestSession(TestBed.inject(SessionStore));
    component = TestBed.createComponent(SaleTicketComponent).componentInstance;
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function committedCapture(paymentId: number): void {
    const request = http.expectOne(`${ACCOUNTING_API_BASE}/payments`);
    request.flush(envelope({ outcome: CommandOutcome.Committed.wire, commandId: request.request.body.commandId, cashSessionId: 1,
      ledgerEventIds: [paymentId], committedAt: '2026-10-07T12:00:00Z', failure: null, paymentId, expenseId: null, settlementId: null, closeSnapshot: null }));
  }
  function missingReceipt(): void {
    const id = component.commands.unresolved()!.commandId;
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${id}`).flush({ code: 404, traceId: 'trace',
      data: { outcome: CommandOutcome.NotFound.wire, commandId: null, failure: null } }, { status: 404, statusText: 'Missing' });
  }
  function finishPaidAttempt(): TicketIdentity {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    committedCapture(1);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0'));
    return identity;
  }
  it('reverses through v2 using a distinct command identity and original payment reference', () => {
    const identity = finishPaidAttempt(); component.reverse(1);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0'));
    const post = http.expectOne(`${ACCOUNTING_API_BASE}/payments/1/reversals`);
    expect(post.request.body.originalPaymentId).toBe(1);
    expect(post.request.body.commandId).not.toBe(identity.operationId);
    post.flush(envelope({ outcome: CommandOutcome.Committed.wire, commandId: post.request.body.commandId,
      cashSessionId: 1, ledgerEventIds: [2], paymentId: 2, committedAt: '2026-10-07T12:00:00Z', failure: null }));
    expect(component.paymentId()).toBeNull(); expect(component.canFinish(SaleAction.Reverse)).toBeFalse();
    http.expectNone(request => request.method === 'POST');
  });
  for (const [reason, expected] of [['', null], [' \t\n ', null], ['  caja ajena  ', 'caja ajena']] as const) {
    it(`sends a DTO-valid capture reason from ticket input ${JSON.stringify(reason)}`, () => {
      component.operationReason = reason;
      component.reserve();
      const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
      const identity: TicketIdentity = reserve.request.body;
      reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
      const capture = http.expectOne(`${ACCOUNTING_API_BASE}/payments`);
      expect(capture.request.body.reason).toBe(expected);
      capture.flush(commandEnvelope(capture.request.body.commandId, { paymentId: 41 }));
      http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0'));
      expect(component.paymentId()).toBe(41);
    });
  }
  it('recovers the global command after navigating to a ticket with no local attempt', () => {
    const command = AccountingCommand.create(AccountingCommandKind.Capture, { amount: '18' });
    component.commands.execute(command).subscribe();
    http.expectOne(`${ACCOUNTING_API_BASE}/payments`).flush(null, { status: 503, statusText: 'Lost' });
    missingReceipt();
    const fixture = TestBed.createComponent(SaleTicketComponent);
    const reopened = fixture.componentInstance;
    expect(reopened.saleBlocked()).toBeTrue();
    reopened.consultReceipt();
    expect(reopened.busy()).toBeTrue();
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${command.commandId}`)
      .flush(commandEnvelope(command.commandId, { paymentId: 41 }));
    expect(reopened.commands.unresolved()).toBeNull();
    expect(reopened.busy()).toBeFalse();
    expect(reopened.paymentId()).toBeNull();
    expect(reopened.canFinish(SaleAction.Commit)).toBeFalse();
    expect(reopened.message()).toContain(CommandOutcome.Committed.label);
    http.expectNone(request => request.method === 'POST');
    fixture.destroy();
  });

  it('blocks a second submit before it creates UUIDs or requests and releases the guard on error', () => {
    const uuid = spyOn(crypto, 'randomUUID').and.callThrough();
    component.reserve();
    const first = http.expectOne(`${API_BASE}/sales/reservations`);
    expect(uuid.calls.count()).toBe(2); // exactly one sale identity and one operation identity
    component.reserve();
    component.commit();
    component.release();
    component.refresh();
    expect(uuid.calls.count()).toBe(2);
    http.expectNone(`${API_BASE}/sales/reservations`);
    first.flush(envelope(null));
    expect(component.busy()).toBeFalse();
    component.reserve();
    expect(uuid.calls.count()).toBe(4);
    http.expectOne(`${API_BASE}/sales/reservations`).flush(envelope(null));
  });

  it('does not send capture for unknown, pending or evidence-free reserve', () => {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    reserve.flush(snapshot(reserve.request.body, PaymentCoverage.Unpaid, '18', SaleStatus.PendingReservation));
    http.expectNone(`${ACCOUNTING_API_BASE}/payments`);
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
  });

  it('cancels an asynchronous reservation before another session can continue it', async () => {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18', SaleStatus.PendingReservation));

    TestBed.inject(SessionStore).changed.next();
    await new Promise(resolve => setTimeout(resolve, 250));

    http.expectNone(`${API_BASE}/sales/${identity.operationId}`);
    http.expectNone(`${ACCOUNTING_API_BASE}/payments`);
    expect(component.busy()).toBeFalse();
  });

  it('keeps immutable requested payment amounts when form edits occur during reserve', () => {
    component.amount = 10;
    component.secondAmount = 8;
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    component.amount = 99;
    component.secondAmount = 99;
    reserve.flush(snapshot(reserve.request.body, PaymentCoverage.Unpaid, '18'));
    const capture = http.expectOne(`${ACCOUNTING_API_BASE}/payments`);
    expect(capture.request.body.amount).toBe('10.00');
    capture.flush(envelope({ outcome: 'FUTURE_OUTCOME' }));
    missingReceipt();
    http.expectNone(`${ACCOUNTING_API_BASE}/payments`);
    expect(component.paymentId()).toBeNull();
  });

  it('does not send release for a paid ticket or commit when refreshed coverage denies it', () => {
    const identity = finishPaidAttempt();
    expect(component.canFinish(SaleAction.Commit)).toBeTrue();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
    component.release();
    http.expectNone(`${API_BASE}/sales/${identity.operationId}/release`);
    component.commit();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    http.expectNone(`${API_BASE}/sales/${identity.operationId}/commit`);
    expect(component.busy()).toBeFalse();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
  });

  it('rejects a response for a different operation without setting paymentId or authorizing actions', () => {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    reserve.flush(snapshot(reserve.request.body, PaymentCoverage.Unpaid, '18'));
    const capture = http.expectOne(`${ACCOUNTING_API_BASE}/payments`);
    capture.flush(envelope({ outcome: CommandOutcome.Committed.wire, commandId: reserve.request.body.saleId }));
    missingReceipt();
    http.expectNone(`${API_BASE}/sales/${reserve.request.body.operationId}`);
    expect(component.paymentId()).toBeNull();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
  });

  it('confirms a Paid snapshot only after refreshing and prevents another terminal write', () => {
    const identity = finishPaidAttempt();
    component.commit();
    http.expectNone(`${API_BASE}/sales/${identity.operationId}/commit`);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0'));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}/commit`).flush(snapshot(identity, PaymentCoverage.Paid, '0', SaleStatus.Committed));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0', SaleStatus.Committed));
    expect(component.busy()).toBeFalse();
    component.commit();
    component.reverse(1);
    http.expectNone(`${API_BASE}/sales/${identity.operationId}/commit`);
    http.expectNone(`${ACCOUNTING_API_BASE}/payments/1/reversals`);
  });

  it('releases empty history but stops if the refreshed snapshot acquired payment history', () => {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(envelope(null));
    component.refresh();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    expect(component.canFinish(SaleAction.Release)).toBeTrue();
    component.release();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    http.expectNone(`${API_BASE}/sales/${identity.operationId}/release`);
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
  });

  it('keeps the first correlated payment when the second split capture fails', () => {
    component.amount = 10;
    component.secondAmount = 8;
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    committedCapture(41);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    http.expectOne(`${ACCOUNTING_API_BASE}/payments`).flush('failed', { status: 503, statusText: 'Unavailable' });
    missingReceipt();

    expect(component.paymentId()).toBe(41);
    expect(component.canFinish(SaleAction.Reverse)).toBeFalse();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
  });

  it('re-dispatches a pending terminal route only to recover its existing command', () => {
    const identity = finishPaidAttempt();
    component.refresh();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0', SaleStatus.CommitPending));
    expect(component.canFinish(SaleAction.Commit)).toBeTrue();

    component.commit();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0', SaleStatus.CommitPending));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}/commit`).flush(snapshot(identity, PaymentCoverage.Paid, '0', SaleStatus.Committed));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0', SaleStatus.Committed));
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
  });

  it('retains the first captured payment when GET fails and waits for recovery before reversal', () => {
    component.amount = 10;
    component.secondAmount = 8;
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    committedCapture(41);
    expect(component.paymentId()).toBe(41);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush('failed', { status: 503, statusText: 'Unavailable' });

    expect(component.paymentId()).toBe(41);
    expect(component.busy()).toBeFalse();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
    expect(component.canFinish(SaleAction.Reverse)).toBeFalse();
    component.reverse(41);
    http.expectNone(`${ACCOUNTING_API_BASE}/payments/41/reversals`);
    http.expectNone(`${ACCOUNTING_API_BASE}/payments`);

    component.refresh();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    expect(component.paymentId()).toBe(41);
    expect(component.canFinish(SaleAction.Reverse)).toBeTrue();
    http.expectNone(`${ACCOUNTING_API_BASE}/payments`);
  });

  it('invalidates the previous actionable snapshot when the second captured payment awaits a failed GET', () => {
    component.amount = 10;
    component.secondAmount = 8;
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    committedCapture(41);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    committedCapture(42);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush('failed', { status: 503, statusText: 'Unavailable' });

    expect(component.paymentId()).toBe(41);
    expect(component.canFinish(SaleAction.Reverse)).toBeFalse();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
    http.expectNone(`${ACCOUNTING_API_BASE}/payments`);
  });
});
