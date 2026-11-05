import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting,HttpTestingController } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of,Subject } from 'rxjs';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingCommand,AccountingCommandKind,ExpenseOperation } from '../domain/accounting-command';
import { JournalEntry,JournalPhase } from '../domain/command-journal';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { ExpenseProjectionState } from '../domain/expense-projection';
import { PaymentMethod } from '../domain/pos-types';
import { SessionState } from '../domain/session-types';
import { IndexedDbCommandJournal } from '../infrastructure/indexed-db-command-journal';
import { CommandHttp } from '../infrastructure/command-http';
import { FOREGROUND_REFRESH } from '../infrastructure/foreground-refresh';
import { commandEnvelope } from '../infrastructure/accounting-command-test-helper';
import { expenseProjectionFixture } from '../infrastructure/expense-projection-test-helper';
import { CommandRuntimeStore } from './command-runtime.store';
import { SessionStore } from './session.store';
describe('expense journal receipt projection recovery through real command HTTP',()=>{
  const context={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10};
  let store:CommandRuntimeStore,http:HttpTestingController,entries:JournalEntry[],session:any,foreground:()=>void;
  const settle=async()=>{for(let i=0;i<15;i++)await Promise.resolve();};
  function refresh(state=AccountingLifecycleState.Active,changed=false){
    http.expectOne(`${ACCOUNTING_API_BASE}/pos/context`).flush({code:200,traceId:'test',data:{state:'Available',context:changed?{...context,deviceId:'other'}:context}});
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/runtime`).flush({code:200,traceId:'test',data:{state:state.wire,contractVersion:'V2',activationAt:'2026-10-07T00:00:00Z',observedAt:'2026-10-08T00:00:00Z'}});
  }
  const command=()=>AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,operation:ExpenseOperation.AccrueAndSettle.wire,amount:'10',category:'supplies',paymentMethod:PaymentMethod.Cash.wire});
  const receipt=(c:AccountingCommand)=>commandEnvelope(c.commandId,{expenseId:5,settlementId:6,ledgerEventIds:[1,2]});
  async function execute(c:AccountingCommand){
    const promise=store.execute(c);refresh();await settle();
    const post=http.expectOne(`${ACCOUNTING_API_BASE}/expenses`);expect(post.request.method).toBe('POST');expect(entries[0].phase).toBe(JournalPhase.AwaitingReceipt);
    post.flush(receipt(c));await settle();return {promise,get:http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${c.commandId}/projection`)};
  }
  beforeEach(async()=>{
    entries=[];session={staff:signal({id:7}),state:signal(SessionState.Authenticated),generation:signal(1),changed:new Subject<void>(),can:()=>true};
    const journal={list:async()=>({entries:[...entries],quarantined:false}),prepare:async(e:JournalEntry)=>{entries.push(e);},
      transition:async(e:JournalEntry,phase:JournalPhase)=>{entries=entries.map(v=>v.command.commandId===e.command.commandId?{...v,phase}:v);}};
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:SessionStore,useValue:session},
      {provide:IndexedDbCommandJournal,useValue:journal},{provide:FOREGROUND_REFRESH,useValue:{subscribe:(fn:()=>void)=>{foreground=fn;return ()=>{};}}}]});
    http=TestBed.inject(HttpTestingController);spyOn(TestBed.inject(CommandHttp),'references').and.returnValue(of(2));
    store=TestBed.inject(CommandRuntimeStore);TestBed.tick();refresh();await settle();expect(store.canWrite()).toBeTrue();
  });
  afterEach(()=>http.verify());
  it('resolves only after receipt plus exact Found and fresh context/lifecycle',async()=>{
    const c=command(),run=await execute(c);expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();
    run.get.flush(expenseProjectionFixture(c));await settle();expect(entries[0].phase).not.toBe(JournalPhase.Resolved);
    refresh();await run.promise;expect(entries[0].phase).toBe(JournalPhase.Resolved);expect(store.canWrite()).toBeTrue();
  });
  for(const [state,code] of [[ExpenseProjectionState.NotFound,404],[ExpenseProjectionState.Unavailable,503],[ExpenseProjectionState.Unknown,503]] as const){
    it(state.wire+' preserves journal and later consult resolves with GET-only evidence',async()=>{
      const c=command(),run=await execute(c);run.get.flush({code,traceId:'test',data:{state:state.wire,projection:null}},{status:code,statusText:'Unavailable'});
      await run.promise;expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();
      const recovery=store.consult();const getReceipt=http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${c.commandId}`);expect(getReceipt.request.method).toBe('GET');getReceipt.flush(receipt(c));await settle();
      http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${c.commandId}/projection`).flush(expenseProjectionFixture(c));await settle();refresh();await recovery;
      expect(entries[0].phase).toBe(JournalPhase.Resolved);http.expectNone(r=>r.method==='POST');
    });
  }
  it('rejects late Found after actor generation invalidation without resolving old evidence',async()=>{
    const c=command(),run=await execute(c);session.staff.set({id:8});session.generation.set(2);session.changed.next();
    run.get.flush(expenseProjectionFixture(c));await run.promise;expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();http.expectNone(r=>r.method==='POST');
  });
  for(const mode of ['http-failure-success-body','success-body-error-code'] as const){
    it('keeps journal blocked and consult GET-only for '+mode,async()=>{
      const c=command(),run=await execute(c),valid=expenseProjectionFixture(c);
      const raw=mode==='success-body-error-code'?{...valid,errorCode:'UNAVAILABLE'}:valid;
      const options=mode==='http-failure-success-body'?{status:503,statusText:'Unavailable'}:{status:200,statusText:'OK'};
      run.get.flush(raw,options);await run.promise;
      expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();
      const recovery=store.consult();http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${c.commandId}`).flush(receipt(c));await settle();
      const get=http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${c.commandId}/projection`);expect(get.request.method).toBe('GET');get.flush(raw,options);await recovery;
      expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();
      await store.execute(command());http.expectNone(r=>r.method==='POST');
    });
  }
  it('changed fresh context keeps exact Found unresolved and blocks next mutation',async()=>{
    const c=command(),run=await execute(c);run.get.flush(expenseProjectionFixture(c));await settle();refresh(AccountingLifecycleState.Active,true);await run.promise;
    expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();
  });
  it('fresh Paused permits historical completion but blocks new mutation',async()=>{
    const c=command(),run=await execute(c);run.get.flush(expenseProjectionFixture(c));await settle();refresh(AccountingLifecycleState.Paused);await run.promise;
    expect(entries[0].phase).toBe(JournalPhase.Resolved);expect(store.canWrite()).toBeFalse();await store.execute(command());http.expectNone(r=>r.method==='POST');
  });
});
