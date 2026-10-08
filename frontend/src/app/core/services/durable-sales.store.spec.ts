import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AccountingCoverage, CommandFailure, CommandOutcome, ExpenseOperation } from '../domain/accounting-command';
import { ReconciliationOutcome } from '../domain/accounting-report';
import { ACCOUNTING_API_BASE, API_BASE } from '../api';
import { AllowedAction, DurableSaleState } from '../domain/durable-sale';
import { PaymentMethod, PaymentStatus, StaffRole } from '../domain/pos-types';
import { PaymentCoverage } from '../domain/ticket-transition';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { commandEnvelope } from '../infrastructure/accounting-command-test-helper';
import { DurableSalesStore } from './durable-sales.store';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';

const identity = { clientInstanceId: '11111111-1111-1111-1111-111111111111', deviceId: 'counter',
  saleId: '22222222-2222-2222-2222-222222222222', operationId: '33333333-3333-3333-3333-333333333333' };
const envelope = (data: unknown) => ({ code: 200, errorCode: null, traceId: 'trace', message: null, retryable: null, data });
const detail = (status = DurableSaleState.Reserved, coverage = PaymentCoverage.Partial, overrides = {}) => ({ ...identity,
  cashSessionId: 1, cashierId: 7, createdBy: 9, status: status.wire, totalAmount: '18.00',
  pendingAmount: coverage === PaymentCoverage.Paid ? '0' : coverage === PaymentCoverage.Unpaid ? '18' : '8',
  paymentCoverage: coverage.wire, hasPaymentHistory: coverage !== PaymentCoverage.Unpaid,
  evidenceValid: true, receipt: 'receipt', reservationRef: 'reservation', blocked: false, retired: false,
  lines: [{ sku: 'SKU', productName: 'Producto persistido', quantity: 1, totalAmount: '18' }],
  payments: coverage === PaymentCoverage.Unpaid ? [] : [{ paymentId: 41, status: PaymentStatus.Captured.wire, method: PaymentMethod.Cash.wire, amount: '10', feeAmount: '0' }],
  pendingCommand: null, allowedActions: AllowedAction.values.map(action => action.wire), ...overrides });

describe('DurableSale closed types and DTO boundary', () => {
  it('preserves the historical actor separately from the cashier and blocks unverified actors', () => {
    const sale = PosWireMapper.durableDetail(envelope(detail()), identity.operationId)!;
    expect(sale.createdBy).toBe(9);
    expect(sale.cashierId).toBe(7);
    expect(sale.valid).toBeTrue();
    for (const createdBy of [undefined, null, 0, -1, 1.5, '9', Number.MAX_SAFE_INTEGER + 1]) {
      const invalid = PosWireMapper.durableDetail(envelope(detail(DurableSaleState.Reserved, PaymentCoverage.Partial, { createdBy })), identity.operationId)!;
      expect(invalid.createdBy).toBeNull();
      expect(invalid.valid).toBeFalse();
      for (const action of AllowedAction.values) expect(invalid.status.permits(action, invalid)).toBeFalse();
    }
  });
  it('round-trips every closed state/action and neutralizes unknown wire', () => {
    for (const state of DurableSaleState.values) expect(DurableSaleState.fromWire(state.wire)).toBe(state);
    for (const action of AllowedAction.values) expect(AllowedAction.fromWire(action.wire)).toBe(action);
    expect(DurableSaleState.fromWire('NEW_BACKEND_STATE')).toBe(DurableSaleState.Unknown);
    expect(AllowedAction.fromWire('UNRECOGNIZED_ACTION')).toBe(AllowedAction.Unknown);
  });
  it('invalidates malformed lines, unknown actions, mismatched identity and evidence', () => {
    for (const overrides of [{ lines: [{ quantity: 0, totalAmount: '18' }] }, { lines: [{ quantity: 1, totalAmount: null }] },
      { allowedActions: ['UNRECOGNIZED_ACTION'] }, { evidenceValid: false }, { pendingAmount: '18', paymentCoverage: PaymentCoverage.Partial.wire },
      { payments: [{ paymentId: 1, status: PaymentStatus.Captured.wire, method: PaymentMethod.Cash.wire, amount: '1.001', feeAmount: '0' }] }]) {
      expect(PosWireMapper.durableDetail(envelope(detail(DurableSaleState.Reserved, PaymentCoverage.Partial, overrides)), identity.operationId)?.valid).toBeFalse();
    }
    expect(PosWireMapper.durableDetail(envelope(detail()), identity.saleId)).toBeNull();
  });
  it('keeps terminal, legacy, unknown and pending views read-only despite hostile allowedActions', () => {
    for (const state of [DurableSaleState.PendingReservation, DurableSaleState.CommitPending, DurableSaleState.ReleasePending,
      DurableSaleState.Committed, DurableSaleState.Released, DurableSaleState.LegacyIncomplete, DurableSaleState.Unknown, DurableSaleState.ReconciliationRequired]) {
      const sale = PosWireMapper.durableDetail(envelope(detail(state, PaymentCoverage.Paid)), identity.operationId)!;
      for (const action of AllowedAction.values) expect(sale.status.permits(action, sale)).toBeFalse();
    }
  });
});

describe('DurableSalesStore existing sale entry', () => {
  let store: DurableSalesStore;
  let http: HttpTestingController;
  let session: SessionStore;
  const url = `${API_BASE}/sales/operations/${identity.operationId}`;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    session = TestBed.inject(SessionStore); authenticateTestSession(session);
    store = TestBed.inject(DurableSalesStore); http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function open(status = DurableSaleState.Reserved, coverage = PaymentCoverage.Partial, overrides = {}): void {
    store.open(identity.operationId); http.expectOne(url).flush(envelope(detail(status, coverage, overrides)));
  }
  it('lists paginated operations using only relative GET', () => {
    store.list('cursor', DurableSaleState.Reserved);
    const request = http.expectOne(request => request.url === `${API_BASE}/sales`);
    expect(request.request.method).toBe('GET'); expect(request.request.params.get('cursor')).toBe('cursor');
    expect(request.request.params.get('state')).toBe(DurableSaleState.Reserved.wire);
    request.flush(envelope({ items: [detail()], nextCursor: 'next' }));
    expect(store.items()[0].status).toBe(DurableSaleState.Reserved); expect(store.nextCursor()).toBe('next');
  });
  it('opens partial/full/terminal/legacy/unknown without POST or new UUIDs', () => {
    const uuid = spyOn(crypto, 'randomUUID').and.callThrough();
    for (const [state, coverage] of [[DurableSaleState.Reserved, PaymentCoverage.Partial], [DurableSaleState.PaymentCaptured, PaymentCoverage.Paid],
      [DurableSaleState.Committed, PaymentCoverage.Paid], [DurableSaleState.LegacyIncomplete, PaymentCoverage.Unpaid], [DurableSaleState.Unknown, PaymentCoverage.Partial]] as const) {
      open(state, coverage); expect(store.detail()?.identity).toEqual(identity);
      http.expectNone(request => request.method === 'POST');
    }
    expect(uuid).not.toHaveBeenCalled();
  });
  it('enables explicit partial capture and full commit only', () => {
    open(); expect(store.can(AllowedAction.CapturePayment)).toBeTrue(); expect(store.can(AllowedAction.Commit)).toBeFalse(); expect(store.can(AllowedAction.Release)).toBeFalse();
    open(DurableSaleState.PaymentCaptured, PaymentCoverage.Paid);
    expect(store.can(AllowedAction.CapturePayment)).toBeFalse(); expect(store.can(AllowedAction.Commit)).toBeTrue();
  });
  it('keeps an unverified historical actor read-only without inferring the cashier as author', () => {
    open(DurableSaleState.PaymentCaptured, PaymentCoverage.Paid, { createdBy: null });
    expect(store.detail()?.createdBy).toBeNull();
    expect(store.detail()?.cashierId).toBe(7);
    expect(store.can(AllowedAction.Commit)).toBeFalse();
    store.execute(AllowedAction.Commit, 'confirmar');
    http.expectNone(request => request.method === 'POST');
  });
  it('denies Auditor/Unknown role all commercial reads and writes', () => {
    for (const role of [StaffRole.Auditor, StaffRole.Unknown]) {
      authenticateTestSession(session, role); store.list(); store.open(identity.operationId); store.execute(AllowedAction.Commit, '');
    }
    http.expectNone(() => true);
  });
  it('honors server ownership/allowedActions and neutralizes 404 without fallback', () => {
    open(DurableSaleState.Reserved, PaymentCoverage.Partial, { allowedActions: [] });
    expect(store.can(AllowedAction.CapturePayment)).toBeFalse();
    store.open(identity.operationId); http.expectOne(url).flush(null, { status: 404, statusText: 'Not Found' });
    expect(store.detail()).toBeNull(); http.expectNone(request => request.method === 'POST');
  });
  it('drops late detail/list after session generation changes', () => {
    store.list(); const list = http.expectOne(request => request.url === `${API_BASE}/sales`);
    store.open(identity.operationId); const read = http.expectOne(url);
    session.expire();
    list.flush(envelope({ items: [detail()], nextCursor: null })); read.flush(envelope(detail()));
    expect(store.detail()).toBeNull(); expect(store.items().length).toBe(0);
  });
  it('performs explicit capture with original identity and no automatic retry after failure', () => {
    open(); store.execute(AllowedAction.CapturePayment, 'completar', '8', '0');
    store.execute(AllowedAction.CapturePayment, 'completar', '8', '0');
    http.expectOne(url).flush(envelope(detail()));
    const capture = http.expectOne(`${ACCOUNTING_API_BASE}/payments`);
    expect(capture.request.body.saleId).toBe(identity.saleId); expect(capture.request.body.operationId).toBe(identity.operationId);
    capture.flush(null, { status: 503, statusText: 'Unavailable' });
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${capture.request.body.commandId}`).flush({ code: 404, traceId: 'trace',
      data: { outcome: CommandOutcome.NotFound.wire, commandId: null, failure: null } }, { status: 404, statusText: 'Missing' });
    expect(store.detail()).toBeNull(); expect(store.busy()).toBeFalse();
    http.expectNone(request => request.method === 'POST');
  });
  for (const [reason, expected] of [['', null], [' \t\n ', null], ['  completar caja ajena  ', 'completar caja ajena']] as const) {
    it(`sends a DTO-valid capture reason from durable input ${JSON.stringify(reason)}`, () => {
      open(); store.execute(AllowedAction.CapturePayment, reason, '8', '0');
      http.expectOne(url).flush(envelope(detail()));
      const capture = http.expectOne(`${ACCOUNTING_API_BASE}/payments`);
      expect(capture.request.body.reason).toBe(expected);
      capture.flush(commandEnvelope(capture.request.body.commandId, { paymentId: 42 }));
      http.expectOne(url).flush(envelope(detail(DurableSaleState.PaymentCaptured, PaymentCoverage.Paid)));
      expect(store.busy()).toBeFalse();
      expect(store.can(AllowedAction.Commit)).toBeTrue();
    });
  }
  it('fresh GET revokes a stale authorization before a terminal POST', () => {
    open(DurableSaleState.PaymentCaptured, PaymentCoverage.Paid); store.execute(AllowedAction.Commit, 'confirmar');
    http.expectOne(url).flush(envelope(detail(DurableSaleState.Reserved, PaymentCoverage.Partial)));
    http.expectNone(request => request.method === 'POST'); expect(store.busy()).toBeFalse();
  });
  it('session expiry before preflight completes emits no POST', () => {
    open(); store.execute(AllowedAction.CapturePayment, '', '8', '0');
    const preflight = http.expectOne(url); session.expire(); preflight.flush(envelope(detail()));
    http.expectNone(request => request.method === 'POST'); expect(store.detail()).toBeNull();
  });
  it('preserves identity through terminal action and refresh', () => {
    open(DurableSaleState.PaymentCaptured, PaymentCoverage.Paid); store.execute(AllowedAction.Commit, 'confirmar');
    http.expectOne(url).flush(envelope(detail(DurableSaleState.PaymentCaptured, PaymentCoverage.Paid)));
    const commit = http.expectOne(`${API_BASE}/sales/${identity.operationId}/commit`);
    expect(commit.request.body.operationId).toBe(identity.operationId); commit.flush(envelope({}));
    http.expectOne(url).flush(envelope(detail(DurableSaleState.Committed, PaymentCoverage.Paid)));
    expect(store.can(AllowedAction.Commit)).toBeFalse(); expect(store.detail()?.identity).toEqual(identity);
  });
});
