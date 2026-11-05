import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController,provideHttpClientTesting} from '@angular/common/http/testing';
import {provideZoneChangeDetection,signal} from '@angular/core';
import {ComponentFixture,TestBed,fakeAsync,flushMicrotasks,tick} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {Subject} from 'rxjs';
import {API_BASE,ACCOUNTING_API_BASE} from '../../core/api';
import {AccountingLifecycleState} from '../../core/domain/accounting-lifecycle';
import {JournalEntry,JournalPhase} from '../../core/domain/command-journal';
import {AllowedAction,DurableSaleState} from '../../core/domain/durable-sale';
import {PosContextState} from '../../core/domain/pos-execution-context';
import {CashSessionStatus,PaymentMethod,PaymentStatus} from '../../core/domain/pos-types';
import {SaleAdmissionOutcome,SaleCommandKind,SaleFingerprintVersion} from '../../core/domain/sale-command';
import {SessionState} from '../../core/domain/session-types';
import {PaymentCoverage} from '../../core/domain/ticket-transition';
import {FOREGROUND_REFRESH} from '../../core/infrastructure/foreground-refresh';
import {IndexedDbCommandJournal} from '../../core/infrastructure/indexed-db-command-journal';
import {SaleAdmissionV2Fingerprint} from '../../core/infrastructure/sale-admission-fingerprint';
import {CommandRuntimeStore} from '../../core/services/command-runtime.store';
import {CounterContextService} from '../../core/services/counter-context.service';
import {SessionStore} from '../../core/services/session.store';
import {SaleTicketComponent} from './sale-ticket.component';

/** Real component, facades, runtime and HTTP; only IndexedDB persistence is replaced by a deterministic port. */
describe('V3 asynchronous reserve retains its durable claim',()=>{
  const context={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10};
  const envelope=(data:unknown)=>({code:200,errorCode:null,message:null,retryable:null,traceId:'test',data});
  let http:HttpTestingController,fixture:ComponentFixture<SaleTicketComponent>,component:SaleTicketComponent;
  let runtime:CommandRuntimeStore,session:any,entries:JournalEntry[],identity:any;
  const contextReads=()=>{
    http.expectOne(`${ACCOUNTING_API_BASE}/pos/context`).flush(envelope({state:PosContextState.Available.wire,context}));
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/runtime`).flush(envelope({state:AccountingLifecycleState.Active.wire,
      contractVersion:'V2',activationAt:'2026-10-07T00:00:00Z',observedAt:'2026-10-08T00:00:00Z'}));flushMicrotasks();
  };
  const cash=()=>http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope([{id:2,terminalId:10,cashierId:7,
    status:CashSessionStatus.Open.wire,openingCash:0}]));
  const detail=(status:DurableSaleState)=>envelope({...identity,cashSessionId:2,cashierId:7,createdBy:7,status:status.wire,
    totalAmount:'18.00',pendingAmount:'18.00',paymentCoverage:PaymentCoverage.Unpaid.wire,hasPaymentHistory:false,
    evidenceValid:status!==DurableSaleState.PendingReservation,blocked:false,retired:false,receipt:'receipt',reservationRef:'ref',
    pendingCommand:null,allowedActions:[AllowedAction.CapturePayment.wire],lines:[{variantId:'variant',sku:'SKU',productName:'Producto',
      quantity:1,originalUnitPrice:'18.00',priceVersion:'P1',totalAmount:'18.00'}],payments:[]});
  const operationRead=()=>http.expectOne(`${API_BASE}/sales/operations/${identity.operationId}`);
  const admit=()=>{
    expect(runtime.hydrated()).toBeTrue();expect(runtime.canWrite()).toBeTrue();expect(component.blockReason()).toBeNull();
    component.reserve();flushMicrotasks();contextReads();cash();flushMicrotasks();
    const post=http.expectOne('/api/v3/sales/reservations');identity=post.request.body;
    expect(entries[0].phase).toBe(JournalPhase.AwaitingReceipt);
    post.flush({code:202,traceId:'test',data:{outcome:SaleAdmissionOutcome.Accepted.wire,failure:null,lineFailures:[],receipt:{
      ...identity,kind:SaleCommandKind.Reserve.wire,actorId:7,cashSessionId:2,payloadHash:'a'.repeat(64),intentId:1,outboxId:2,
      acceptedAt:'2026-10-08T00:00:00Z',fingerprintVersion:SaleFingerprintVersion.AdmissionV2.wire}}});flushMicrotasks();
  };
  beforeEach(fakeAsync(()=>{
    entries=[];session={staff:signal({id:7}),generation:signal(1),state:signal(SessionState.Authenticated),changed:new Subject<void>(),can:()=>true};
    spyOn(SaleAdmissionV2Fingerprint.prototype,'hash').and.returnValue(Promise.resolve('a'.repeat(64)));
    const journal={list:async()=>({entries:[...entries],quarantined:false}),prepare:async(entry:JournalEntry)=>{
      if(entries.some(value=>value.phase!==JournalPhase.Resolved))throw Error('claim');entries.push(entry);},
      transition:async(entry:JournalEntry,phase:JournalPhase)=>{entries=entries.map(value=>value.command.commandId===entry.command.commandId?{...value,phase}:value);}};
    TestBed.configureTestingModule({imports:[SaleTicketComponent],providers:[provideZoneChangeDetection({eventCoalescing:true}),
      provideHttpClient(),provideHttpClientTesting(),provideRouter([]),{provide:SessionStore,useValue:session},
      {provide:IndexedDbCommandJournal,useValue:journal},{provide:FOREGROUND_REFRESH,useValue:{subscribe:()=>()=>{}}},
      {provide:CounterContextService,useValue:{load:()=>undefined,loading:signal(false),catalog:signal({version:'v1',stale:false,
        importedAt:null,validUntil:null,items:[{sku:'SKU',name:'Producto',variantId:'variant',priceVersion:'P1',unitPrice:18}]}),
        openSession:signal({id:2,terminalId:10}),blockReason:()=>null}}]});
    http=TestBed.inject(HttpTestingController);runtime=TestBed.inject(CommandRuntimeStore);fixture=TestBed.createComponent(SaleTicketComponent);
    component=fixture.componentInstance;component.selectedSku='SKU';component.addSelected();fixture.detectChanges();contextReads();
    expect(runtime.hydrated()).toBeTrue();http.verify();
  }));
  afterEach(()=>http.verify());

  it('PendingReservation→Reserved resolves the original claim without capturing money',fakeAsync(()=>{
    admit();operationRead().flush(detail(DurableSaleState.PendingReservation));flushMicrotasks();
    expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(runtime.canWrite()).toBeFalse();
    tick(100);operationRead().flush(detail(DurableSaleState.Reserved));flushMicrotasks();
    expect(entries[0].phase).toBe(JournalPhase.Resolved);
    http.expectOne(`${API_BASE}/sales/${identity.operationId}`).flush(detail(DurableSaleState.Reserved));flushMicrotasks();
    expect(component.snapshot()?.status.wire).toBe('RESERVED');expect(component.paymentId()).toBeNull();
    http.expectNone(request=>request.method==='POST');
  }));

  it('bounded pending timeout preserves AwaitingRefresh and never captures or rePOSTs',fakeAsync(()=>{
    admit();
    for(let read=0;read<20;read+=1){operationRead().flush(detail(DurableSaleState.PendingReservation));flushMicrotasks();if(read<19)tick(100);}
    expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(runtime.canWrite()).toBeFalse();
    expect(component.paymentId()).toBeNull();expect(component.busy()).toBeFalse();http.expectNone(request=>request.method==='POST');
  }));

  it('actor change while the authoritative GET is in flight suppresses delivery',fakeAsync(()=>{
    admit();const read=operationRead();session.staff.set(null);session.generation.set(2);session.changed.next();
    read.flush(detail(DurableSaleState.Reserved));flushMicrotasks();tick(100);
    expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(component.operationRef()).toBeNull();
    expect(component.paymentId()).toBeNull();http.expectNone(request=>request.method==='POST'||request.url.includes('/sales/'));
  }));

  for(const response of [null,{code:404,errorCode:null,message:null,retryable:null,traceId:'test',data:null}]){
    it('unknown or NotFound GET retains the claim without capture',fakeAsync(()=>{
      admit();operationRead().flush(response);flushMicrotasks();
      expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(runtime.canWrite()).toBeFalse();
      expect(component.paymentId()).toBeNull();http.expectNone(request=>request.method==='POST');
    }));
  }
});
