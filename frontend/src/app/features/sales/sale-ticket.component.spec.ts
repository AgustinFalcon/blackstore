import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { API_BASE } from '../../core/api';
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

  function finishPaidAttempt(): TicketIdentity {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    http.expectOne(`${API_BASE}/payments`).flush(envelope({ ...identity, paymentId: 1, status: PaymentStatus.Captured.wire, amount: '18', feeAmount: '0.50' }));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Paid, '0'));
    return identity;
  }

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
    http.expectNone(`${API_BASE}/payments`);
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
  });

  it('cancels an asynchronous reservation before another session can continue it', fakeAsync(() => {
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18', SaleStatus.PendingReservation));

    TestBed.inject(SessionStore).changed.next();
    tick(250);

    http.expectNone(`${API_BASE}/sales/${identity.operationId}`);
    http.expectNone(`${API_BASE}/payments`);
    expect(component.busy()).toBeFalse();
  }));

  it('keeps immutable requested payment amounts when form edits occur during reserve', () => {
    component.amount = 10;
    component.secondAmount = 8;
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    component.amount = 99;
    component.secondAmount = 99;
    reserve.flush(snapshot(reserve.request.body, PaymentCoverage.Unpaid, '18'));
    const capture = http.expectOne(`${API_BASE}/payments`);
    expect(capture.request.body.amount).toBe('10.00');
    capture.flush(envelope({ ...reserve.request.body, paymentId: 1, status: PaymentStatus.Pending.wire, amount: '10', feeAmount: '0.50' }));
    http.expectNone(`${API_BASE}/payments`);
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
    http.expectOne(`${API_BASE}/payments`).flush(envelope({ ...reserve.request.body,
      operationId: reserve.request.body.saleId, paymentId: 1, status: PaymentStatus.Captured.wire, amount: '18', feeAmount: '0.50' }));
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
    http.expectNone(`${API_BASE}/payments/1/reversals`);
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
    http.expectOne(`${API_BASE}/payments`).flush(envelope({
      ...identity, paymentId: 41, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50',
    }));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    http.expectOne(`${API_BASE}/payments`).flush('failed', { status: 503, statusText: 'Unavailable' });

    expect(component.paymentId()).toBe(41);
    expect(component.canFinish(SaleAction.Reverse)).toBeTrue();
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
    http.expectOne(`${API_BASE}/payments`).flush(envelope({
      ...identity, paymentId: 41, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50',
    }));
    expect(component.paymentId()).toBe(41);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush('failed', { status: 503, statusText: 'Unavailable' });

    expect(component.paymentId()).toBe(41);
    expect(component.busy()).toBeFalse();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
    expect(component.canFinish(SaleAction.Reverse)).toBeFalse();
    component.reverse(41);
    http.expectNone(`${API_BASE}/payments/41/reversals`);
    http.expectNone(`${API_BASE}/payments`);

    component.refresh();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    expect(component.paymentId()).toBe(41);
    expect(component.canFinish(SaleAction.Reverse)).toBeTrue();
    http.expectNone(`${API_BASE}/payments`);
  });

  it('invalidates the previous actionable snapshot when the second captured payment awaits a failed GET', () => {
    component.amount = 10;
    component.secondAmount = 8;
    component.reserve();
    const reserve = http.expectOne(`${API_BASE}/sales/reservations`);
    const identity: TicketIdentity = reserve.request.body;
    reserve.flush(snapshot(identity, PaymentCoverage.Unpaid, '18'));
    http.expectOne(`${API_BASE}/payments`).flush(envelope({
      ...identity, paymentId: 41, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50',
    }));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(snapshot(identity, PaymentCoverage.Partial, '8'));
    http.expectOne(`${API_BASE}/payments`).flush(envelope({
      ...identity, paymentId: 42, status: PaymentStatus.Captured.wire, amount: '8', feeAmount: '0',
    }));
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush('failed', { status: 503, statusText: 'Unavailable' });

    expect(component.paymentId()).toBe(41);
    expect(component.canFinish(SaleAction.Reverse)).toBeFalse();
    expect(component.canFinish(SaleAction.Commit)).toBeFalse();
    expect(component.canFinish(SaleAction.Release)).toBeFalse();
    http.expectNone(`${API_BASE}/payments`);
  });
});
