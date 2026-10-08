import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController,provideHttpClientTesting} from '@angular/common/http/testing';
import {signal,provideZoneChangeDetection} from '@angular/core';
import {ComponentFixture,TestBed,fakeAsync,flushMicrotasks,tick} from '@angular/core/testing';
import {Subject} from 'rxjs';
import {SaleTicketComponent} from './sale-ticket.component';
import {CommandRuntimeStore} from '../../core/services/command-runtime.store';
import {SessionStore} from '../../core/services/session.store';
import {CounterContextService} from '../../core/services/counter-context.service';
import {IndexedDbCommandJournal} from '../../core/infrastructure/indexed-db-command-journal';
import {SaleAdmissionFingerprint} from '../../core/infrastructure/sale-admission-fingerprint';
import {JournalEntry,JournalPhase} from '../../core/domain/command-journal';
import {SessionState} from '../../core/domain/session-types';
import {PosContextState} from '../../core/domain/pos-execution-context';
import {AccountingLifecycleState} from '../../core/domain/accounting-lifecycle';
import {SaleAdmissionOutcome,SaleCommandKind} from '../../core/domain/sale-command';
import {AllowedAction,DurableSaleState} from '../../core/domain/durable-sale';
import {CashSessionStatus,PaymentMethod,PaymentStatus} from '../../core/domain/pos-types';
import {PaymentCoverage} from '../../core/domain/ticket-transition';
import {API_BASE,ACCOUNTING_API_BASE} from '../../core/api';
import {commandEnvelope} from '../../core/infrastructure/accounting-command-test-helper';

/** Real component, facades, runtime and HTTP; journal persistence is a deterministic test port. */
describe('Accepted asynchronous Reserve keeps its claim until authoritative Reserved',()=>{
  const context={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10};
  const envelope=(data:unknown)=>({code:200,errorCode:null,message:null,retryable:null,traceId:'test',data});
  let http:HttpTestingController,fixture:ComponentFixture<SaleTicketComponent>,component:SaleTicketComponent,runtime:CommandRuntimeStore,session:any,entries:JournalEntry[],identity:any;
  const contextReads=()=>{
    http.expectOne(`${ACCOUNTING_API_BASE}/pos/context`).flush(envelope({state:PosContextState.Available.wire,context}));
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/runtime`).flush(envelope({state:AccountingLifecycleState.Active.wire,contractVersion:'V2',activationAt:'2026-10-07T00:00:00Z',observedAt:'2026-10-08T00:00:00Z'}));flushMicrotasks();
  };
  const cash=()=>http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope([{id:2,terminalId:10,cashierId:7,status:CashSessionStatus.Open.wire,openingCash:0}]));
  const detail=(status:DurableSaleState,paid=false)=>envelope({...identity,cashSessionId:2,cashierId:7,createdBy:7,status:status.wire,totalAmount:'18',pendingAmount:paid?'0':'18',paymentCoverage:(paid?PaymentCoverage.Paid:PaymentCoverage.Unpaid).wire,hasPaymentHistory:paid,evidenceValid:status!==DurableSaleState.PendingReservation,blocked:false,retired:false,receipt:'receipt',reservationRef:'ref',pendingCommand:null,allowedActions:[AllowedAction.CapturePayment.wire],
    lines:[{sku:'SKU',productName:'Product',quantity:1,totalAmount:'18'}],payments:paid?[{paymentId:41,status:PaymentStatus.Captured.wire,method:PaymentMethod.Cash.wire,amount:'18',feeAmount:'0'}]:[]});
  const operationRead=()=>http.expectOne(`${API_BASE}/sales/operations/${identity.operationId}`);
  const admit=()=>{
    expect(runtime.hydrated()).toBeTrue();expect(runtime.canWrite()).toBeTrue();expect(component.blockReason()).toBeNull();
    component.reserve();flushMicrotasks();contextReads();cash();flushMicrotasks();
    const post=http.expectOne(`${ACCOUNTING_API_BASE}/sales/reservations`);identity=post.request.body;
    expect(entries[0].phase).toBe(JournalPhase.AwaitingReceipt);
    post.flush({code:202,traceId:'test',data:{outcome:SaleAdmissionOutcome.Accepted.wire,failure:null,receipt:{...identity,kind:SaleCommandKind.Reserve.wire,actorId:7,cashSessionId:2,payloadHash:'a'.repeat(64),intentId:1,outboxId:2,acceptedAt:'2026-10-08T00:00:00Z'}}});flushMicrotasks();
  };
  beforeEach(fakeAsync(()=>{
    entries=[];session={staff:signal({id:7}),generation:signal(1),state:signal(SessionState.Authenticated),changed:new Subject<void>(),can:()=>true};
    spyOn(SaleAdmissionFingerprint,'hash').and.returnValue(Promise.resolve('a'.repeat(64)));
    const journal={list:async()=>({entries:[...entries],quarantined:false}),prepare:async(entry:JournalEntry)=>{if(entries.some(e=>e.phase!==JournalPhase.Resolved))throw Error('claim');entries.push(entry);},
      transition:async(entry:JournalEntry,phase:JournalPhase)=>{entries=entries.map(e=>e.command.commandId===entry.command.commandId?{...e,phase}:e);}};
    // Match the application's Zone-based change detection; fakeAsync controls polling timers.
    // Never call ApplicationRef/TestBed.tick from this fixture's lifecycle.
    TestBed.configureTestingModule({imports:[SaleTicketComponent],providers:[provideZoneChangeDetection({eventCoalescing:true}),provideHttpClient(),provideHttpClientTesting(),{provide:SessionStore,useValue:session},{provide:IndexedDbCommandJournal,useValue:journal},
      {provide:CounterContextService,useValue:{load:()=>{},catalog:signal(null),openSession:signal({id:2,terminalId:10}),blockReason:()=>null}}]});
    http=TestBed.inject(HttpTestingController);runtime=TestBed.inject(CommandRuntimeStore);fixture=TestBed.createComponent(SaleTicketComponent);component=fixture.componentInstance;
    component.sku='SKU';component.productName='Product';component.variantId='variant';
    fixture.detectChanges(); // ngOnInit/component effects + initial root runtime hydration.
    contextReads(); // Assert and complete the actual bootstrap GETs, not reserve preflight.
    expect(runtime.hydrated()).toBeTrue();expect(runtime.canWrite()).toBeTrue();http.verify();
  }));
  afterEach(()=>http.verify());
  it('Accepted→PendingReservation→Reserved resolves the original claim before Capture',fakeAsync(()=>{
    admit();operationRead().flush(detail(DurableSaleState.PendingReservation));flushMicrotasks();
    expect(runtime.canWrite()).toBeFalse();expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(component.busy()).toBeTrue();
    http.expectNone(r=>r.method==='POST');tick(100);operationRead().flush(detail(DurableSaleState.Reserved));flushMicrotasks();
    expect(entries[0].phase).toBe(JournalPhase.Resolved);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(detail(DurableSaleState.Reserved));flushMicrotasks();
    contextReads();operationRead().flush(detail(DurableSaleState.Reserved));cash();flushMicrotasks();
    const capture=http.expectOne(`${ACCOUNTING_API_BASE}/payments`);expect(capture.request.body.operationId).toBe(identity.operationId);expect(capture.request.body.amount).toBe('18.00');
    capture.flush(commandEnvelope(capture.request.body.commandId,{paymentId:41}));flushMicrotasks();
    operationRead().flush(detail(DurableSaleState.PaymentCaptured,true));flushMicrotasks();
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(detail(DurableSaleState.PaymentCaptured,true));flushMicrotasks();
    expect(component.paymentId()).toBe(41);expect(entries.every(e=>e.phase===JournalPhase.Resolved)).toBeTrue();http.expectNone(r=>r.method==='POST');
  }));
  it('bounded pending timeout preserves AwaitingRefresh and never captures or rePOSTs',fakeAsync(()=>{
    admit();
    for(let read=0;read<20;read++){operationRead().flush(detail(DurableSaleState.PendingReservation));flushMicrotasks();if(read<19)tick(100);}
    expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(runtime.canWrite()).toBeFalse();expect(component.paymentId()).toBeNull();expect(component.busy()).toBeFalse();http.expectNone(r=>r.method==='POST');
  }));
  it('session change while a GET is in flight suppresses delivery and subsequent reads',fakeAsync(()=>{
    admit();const read=operationRead();
    session.staff.set(null);session.generation.set(2);session.changed.next();read.flush(detail(DurableSaleState.Reserved));flushMicrotasks();tick(100);
    expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(component.operationRef()).toBeNull();expect(component.paymentId()).toBeNull();http.expectNone(r=>r.method==='POST'||r.url.includes('/sales/'));
  }));
  for(const response of [null,{code:404,errorCode:null,message:null,retryable:null,traceId:'test',data:null}]){
    it('unknown or NotFound GET retains the claim without capture',fakeAsync(()=>{
      admit();operationRead().flush(response);flushMicrotasks();
      expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(runtime.canWrite()).toBeFalse();expect(component.paymentId()).toBeNull();http.expectNone(r=>r.method==='POST');
    }));
  }
});
