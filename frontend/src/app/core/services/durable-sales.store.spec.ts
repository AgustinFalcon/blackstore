import { signal } from '@angular/core';
import { of } from 'rxjs';
import { AccountingCommandsStore } from './accounting-commands.store';
import { SaleCommandsStore } from './sale-commands.store';
import { SaleCommandKind,SaleAdmissionOutcome } from '../domain/sale-command';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AccountingCoverage, CommandFailure, CommandOutcome, ExpenseOperation } from '../domain/accounting-command';
import { ReconciliationOutcome } from '../domain/accounting-report';
import { ACCOUNTING_API_BASE, API_BASE } from '../api';
import { AllowedAction, DurableSaleState,PaymentReversibility } from '../domain/durable-sale';
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
  payments: coverage === PaymentCoverage.Unpaid ? [] : [{ paymentId: 41, status: PaymentStatus.Captured.wire, method: PaymentMethod.Cash.wire, amount: coverage === PaymentCoverage.Paid ? '18' : '10', feeAmount: '0' }],
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

describe('DurableSalesStore v2 existing sale entry',()=>{
  let store:DurableSalesStore;let http:HttpTestingController;let accounting:any;let sales:any;
  const url=`${API_BASE}/sales/operations/${identity.operationId}`;
  beforeEach(()=>{
    accounting={unresolved:signal(null),blocked:signal(CommandFailure.None),execute:jasmine.createSpy(),consult:jasmine.createSpy()};
    sales={unresolved:signal(null),execute:jasmine.createSpy().and.returnValue(of({outcome:SaleAdmissionOutcome.Unknown,receipt:null})),consult:jasmine.createSpy()};
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:AccountingCommandsStore,useValue:accounting},{provide:SaleCommandsStore,useValue:sales}]});
    authenticateTestSession(TestBed.inject(SessionStore));store=TestBed.inject(DurableSalesStore);http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>http.verify());
  const open=(status=DurableSaleState.Reserved,coverage=PaymentCoverage.Partial)=>{
    store.open(identity.operationId);http.expectOne(url).flush(envelope(detail(status,coverage)));
  };
  it('opens existing identity through GET only without manufacturing a sale',()=>{
    const uuid=spyOn(crypto,'randomUUID').and.callThrough();open();expect(store.detail()?.identity).toEqual(identity);expect(uuid).not.toHaveBeenCalled();http.expectNone(r=>r.method==='POST');
  });
  it('isolates late list and detail responses after identity changes',()=>{
    store.open(identity.operationId);const request=http.expectOne(url);TestBed.inject(SessionStore).changed.next();request.flush(envelope(detail()));expect(store.detail()).toBeNull();
  });
  for(const [action,kind,coverage] of [[AllowedAction.Commit,SaleCommandKind.Commit,PaymentCoverage.Paid],[AllowedAction.Release,SaleCommandKind.Release,PaymentCoverage.Unpaid]] as const){
    it('pre-reads and delegates '+kind.wire+' with original identity to saga v2',()=>{
      open(DurableSaleState.Reserved,coverage);store.execute(action,'motivo');http.expectOne(url).flush(envelope(detail(DurableSaleState.Reserved,coverage)));
      const command=sales.execute.calls.mostRecent().args[0];expect(command.kind).toBe(kind);expect(command.identity).toEqual(identity);expect(command.cashSessionId).toBe(1);
      http.expectNone(r=>r.method==='POST');expect(store.detail()).toBeNull();
    });
  }
  it('blocks all mutations while common journal is pending or lifecycle is paused',()=>{
    open();accounting.blocked.set(CommandFailure.Paused);for(const action of AllowedAction.values)expect(store.can(action)).toBeFalse();http.expectNone(r=>r.method==='POST');
  });
  it('creates capture without any unsupported fee field',()=>{
    accounting.execute.and.returnValue(of(PosWireMapper.commandReceipt(null,'')));
    open();store.execute(AllowedAction.CapturePayment,'motivo','8',PaymentMethod.Card);http.expectOne(url).flush(envelope(detail()));
    const command=accounting.execute.calls.mostRecent().args[0];expect(command.body['feeAmount']).toBeUndefined();expect(command.body['paymentMethod']).toBe(PaymentMethod.Card.wire);
  });
  it('blocks every new reverse after a refund even while another capture remains',()=>{
    const payments=[
      {paymentId:41,status:PaymentStatus.Captured.wire,method:PaymentMethod.Cash.wire,amount:'10',feeAmount:'0'},
      {paymentId:42,status:PaymentStatus.Captured.wire,method:PaymentMethod.Card.wire,amount:'8',feeAmount:'0'},
      {paymentId:43,status:PaymentStatus.Refunded.wire,method:PaymentMethod.Cash.wire,amount:'10',feeAmount:'0',originalPaymentId:41},
    ];
    store.open(identity.operationId);http.expectOne(url).flush(envelope(detail(DurableSaleState.PaymentCaptured,PaymentCoverage.Partial,{pendingAmount:'10',payments})));
    expect(store.detail()?.valid).toBeTrue();expect(store.detail()?.payments[2].originalPaymentId).toBe(41);
    for(const payment of store.detail()!.payments.filter(p=>p.status===PaymentStatus.Captured))expect(payment.reversibility).toBe(PaymentReversibility.Unknown);
    expect(store.can(AllowedAction.ReversePayment)).toBeFalse();store.execute(AllowedAction.ReversePayment,'refund',undefined,PaymentMethod.Cash,41);
    store.execute(AllowedAction.ReversePayment,'refund',undefined,PaymentMethod.Card,42);
    expect(accounting.execute).not.toHaveBeenCalled();http.expectNone(r=>r.method==='POST'||r.url===url);
  });
  it('checks newly refunded evidence again before handing a reverse to the journal',()=>{
    open();store.execute(AllowedAction.ReversePayment,'refund',undefined,PaymentMethod.Cash,41);
    const payments=[{paymentId:41,status:PaymentStatus.Captured.wire,method:PaymentMethod.Cash.wire,amount:'10',feeAmount:'0'},
      {paymentId:42,status:PaymentStatus.Refunded.wire,method:PaymentMethod.Cash.wire,amount:'10',feeAmount:'0'}];
    http.expectOne(url).flush(envelope(detail(DurableSaleState.PaymentCaptured,PaymentCoverage.Unpaid,{hasPaymentHistory:true,payments})));
    expect(accounting.execute).not.toHaveBeenCalled();http.expectNone(r=>r.method==='POST');
  });
});
