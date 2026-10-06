import { firstValueFrom, of, Subject, throwError } from 'rxjs';
import { PaymentMethod, PaymentStatus, SaleStatus } from '../../core/domain/pos-types';
import { PaymentCoverage, TicketMoney } from '../../core/domain/ticket-transition';
import { CapturePaymentStep, RefreshTicketStep, ReserveTicketStep, TicketAttemptContext, TicketFlowPort, TicketPaymentJourney } from './ticket-steps';

describe('TicketPaymentJourney (T02/T05)', () => {
  const identity = {
    clientInstanceId: '11111111-1111-1111-1111-111111111111', deviceId: 'terminal-1',
    saleId: '22222222-2222-2222-2222-222222222222', operationId: '33333333-3333-3333-3333-333333333333',
  };
  const money = (value: string) => TicketMoney.fromDecimal(value)!;
  const context: TicketAttemptContext = Object.freeze({ identity, reservation: identity, payments: Object.freeze([
    { identity, method: PaymentMethod.Cash, amount: money('10'), fee: money('0.50') },
    { identity, method: PaymentMethod.Card, amount: money('8'), fee: money('0') },
  ]) });
  const envelope = (data: unknown) => ({ code: 200, errorCode: null, traceId: 'trace', message: null, retryable: null, data });
  const snapshot = (pending: string, coverage: PaymentCoverage, status = SaleStatus.Reserved) => envelope({
    ...identity, status: status.wire, receipt: 'receipt', reservationRef: 'ref', evidenceValid: true,
    totalAmount: '18', pendingAmount: pending, paymentCoverage: coverage.wire, hasPaymentHistory: coverage !== PaymentCoverage.Unpaid,
    blocked: false, retired: false,
  });
  let port: jasmine.SpyObj<TicketFlowPort>;
  let journey: TicketPaymentJourney;
  beforeEach(() => {
    port = jasmine.createSpyObj<TicketFlowPort>('port', ['reserve', 'capture', 'refresh']);
    port.reserve.and.returnValue(of(snapshot('18', PaymentCoverage.Unpaid)));
    journey = new TicketPaymentJourney(new ReserveTicketStep(port), new CapturePaymentStep(port), new RefreshTicketStep(port));
  });

  it('waits for valid capture and refreshed balance before the second capture', async () => {
    const first = new Subject<unknown>();
    const refresh = new Subject<unknown>();
    port.capture.and.callFake((attempt) => attempt === context.payments[0] ? first : of(envelope({
      ...identity, paymentId: 2, status: PaymentStatus.Captured.wire, amount: '8', feeAmount: '0',
    })));
    port.refresh.and.returnValues(refresh, of(snapshot('0', PaymentCoverage.Paid)));
    const result = firstValueFrom(journey.execute(context));
    expect(port.capture.calls.count()).toBe(1);
    expect(port.refresh).not.toHaveBeenCalled();
    first.next(envelope({ ...identity, paymentId: 1, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50' }));
    first.complete();
    expect(port.capture.calls.count()).toBe(1);
    refresh.next(snapshot('8', PaymentCoverage.Partial));
    refresh.complete();
    expect((await result).payments.length).toBe(2);
    expect(port.capture.calls.count()).toBe(2);
    expect(port.refresh.calls.count()).toBe(2);
  });

  it('stops on pending, reconciliation, Unknown and missing reservation payload', async () => {
    for (const status of [SaleStatus.PendingReservation, SaleStatus.ReconciliationRequired, SaleStatus.Unknown]) {
      port.reserve.and.returnValue(of(snapshot('18', PaymentCoverage.Unpaid, status)));
      await expectAsync(firstValueFrom(journey.execute(context))).toBeRejected();
    }
    port.reserve.and.returnValue(of(envelope(null)));
    await expectAsync(firstValueFrom(journey.execute(context))).toBeRejected();
    expect(port.capture).not.toHaveBeenCalled();
  });

  it('does not split after unknown or mismatched capture response', async () => {
    for (const changes of [{ status: PaymentStatus.Pending.wire }, { status: PaymentStatus.Unknown.wire },
      { operationId: identity.saleId }, { amount: '9' }, { feeAmount: '0' }, { paymentId: 0 }]) {
      port.capture.and.returnValue(of(envelope({ ...identity, paymentId: 1, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50', ...changes })));
      await expectAsync(firstValueFrom(journey.execute(context))).toBeRejected();
    }
    expect(port.capture.calls.count()).toBe(6);
    expect(port.refresh).not.toHaveBeenCalled();
  });

  it('does not split after a stale or differently correlated refresh', async () => {
    port.capture.and.returnValue(of(envelope({ ...identity, paymentId: 1, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50' })));
    port.refresh.and.returnValue(of(snapshot('18', PaymentCoverage.Unpaid)));
    await expectAsync(firstValueFrom(journey.execute(context))).toBeRejected();
    expect(port.capture.calls.count()).toBe(1);
    port.refresh.and.returnValue(of(envelope({ ...identity, operationId: identity.saleId })));
    await expectAsync(firstValueFrom(journey.execute(context))).toBeRejected();
    expect(port.capture.calls.count()).toBe(2);
  });

  it('publishes a confirmed first capture before a later split step fails', async () => {
    const progress: Array<{ pending: string; paymentId: number }> = [];
    port.capture.and.returnValues(
      of(envelope({ ...identity, paymentId: 1, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50' })),
      throwError(() => new Error('second capture failed')),
    );
    port.refresh.and.returnValue(of(snapshot('8', PaymentCoverage.Partial)));
    journey = new TicketPaymentJourney(
      new ReserveTicketStep(port), new CapturePaymentStep(port), new RefreshTicketStep(port),
      (result) => progress.push({ pending: result.snapshot.pending!.decimal, paymentId: result.payments[0].paymentId }),
    );

    await expectAsync(firstValueFrom(journey.execute(context))).toBeRejected();

    expect(progress).toEqual([{ pending: '8.00', paymentId: 1 }]);
  });

  it('retains correlated capture evidence before refresh but never advances on a failed or invalid read', async () => {
    for (const response of [
      throwError(() => new Error('GET unavailable')),
      of(snapshot('18', PaymentCoverage.Unpaid)),
      of(envelope({ ...identity, operationId: identity.saleId })),
    ]) {
      const captured: number[] = [];
      const progress = jasmine.createSpy('progress');
      const refresh = new Subject<unknown>();
      port.capture.calls.reset();
      port.capture.and.returnValue(of(envelope({
        ...identity, paymentId: 41, status: PaymentStatus.Captured.wire, amount: '10', feeAmount: '0.50',
      })));
      port.refresh.and.returnValue(refresh);
      journey = new TicketPaymentJourney(
        new ReserveTicketStep(port), new CapturePaymentStep(port), new RefreshTicketStep(port),
        progress, (payment) => captured.push(payment.paymentId),
      );
      const result = firstValueFrom(journey.execute(context));
      expect(captured).toEqual([41]);
      expect(progress).not.toHaveBeenCalled();
      expect(port.capture.calls.count()).toBe(1);

      response.subscribe(refresh);
      await expectAsync(result).toBeRejected();

      expect(captured).toEqual([41]);
      expect(progress).not.toHaveBeenCalled();
      expect(port.capture.calls.count()).toBe(1);
    }
  });
});
