import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { AccountingCommand, AccountingCommandKind, CommandOutcome,ExpenseOperation } from '../domain/accounting-command';
import {SaleCommand,SaleCommandKind,SaleAdmissionOutcome} from '../domain/sale-command';
import {SaleAdmissionFingerprint} from '../infrastructure/sale-admission-fingerprint';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { JournalEntry, JournalPhase } from '../domain/command-journal';
import { SessionState } from '../domain/session-types';
import { IndexedDbCommandJournal } from '../infrastructure/indexed-db-command-journal';
import { CommandHttp } from '../infrastructure/command-http';
import { commandEnvelope } from '../infrastructure/accounting-command-test-helper';
import { SessionStore } from './session.store';
import { CommandRuntimeStore } from './command-runtime.store';

describe('CommandRuntimeStore durable admission and recovery', () => {
  const context = { clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10 };
  const envelope = (data:unknown) => ({ code:200,traceId:'test',data });
  let store:CommandRuntimeStore; let entries:JournalEntry[]; let corrupt:boolean;
  let session:any; let api:any; let journal:any;
  const settle = async () => { for(let i=0;i<8;i++) await Promise.resolve(); };
  const command = () => AccountingCommand.create(AccountingCommandKind.Open,{ terminalId:10,cashierId:7,openingCash:'0' });
  beforeEach(async () => {
    entries=[]; corrupt=false;
    session={ staff:signal({id:7}),state:signal(SessionState.Authenticated),generation:signal(1),changed:new Subject<void>(),can:()=>true };
    api={
      context:jasmine.createSpy().and.callFake(()=>of(envelope({state:'Available',context}))),
      lifecycle:jasmine.createSpy().and.callFake(()=>of(envelope({state:AccountingLifecycleState.Active.wire,contractVersion:'V2',activationAt:'2026-10-07T12:00:00Z',observedAt:'2026-10-08T12:00:00Z'}))),
      references:jasmine.createSpy().and.returnValue(of(null)),
      post:jasmine.createSpy().and.callFake((c:AccountingCommand)=>of(commandEnvelope(c.commandId))),
      receipt:jasmine.createSpy().and.callFake((c:AccountingCommand)=>of(commandEnvelope(c.commandId))),
      refresh:jasmine.createSpy().and.returnValue(of(true)),
    };
    journal={
      list:jasmine.createSpy().and.callFake(async()=>({entries:[...entries],quarantined:corrupt})),
      prepare:jasmine.createSpy().and.callFake(async(e:JournalEntry)=>{ if(entries.some(x=>x.phase!==JournalPhase.Resolved)) throw Error('claim'); entries.push(e); }),
      transition:jasmine.createSpy().and.callFake(async(e:JournalEntry,phase:JournalPhase)=>{entries=entries.map(x=>x.command.commandId===e.command.commandId?{...x,phase}:x);}),
    };
    TestBed.configureTestingModule({providers:[{provide:SessionStore,useValue:session},{provide:CommandHttp,useValue:api},{provide:IndexedDbCommandJournal,useValue:journal}]});
    store=TestBed.inject(CommandRuntimeStore); TestBed.tick(); await settle();
  });
  it('confirms prepare and phase transaction before the only POST; resolves only after refresh',async()=>{
    let release!:()=>void; journal.prepare.and.callFake((e:JournalEntry)=>new Promise<void>(resolve=>{release=()=>{entries.push(e);resolve();};}));
    const c=command(); const promise=store.execute(c); await settle(); expect(api.post).not.toHaveBeenCalled();
    release(); await promise; expect(api.post.calls.count()).toBe(1); expect(entries[0].phase).toBe(JournalPhase.Resolved);
    expect(api.refresh).toHaveBeenCalled(); expect(store.canWrite()).toBeTrue();
  });
  it('rejects storage failure before sending',async()=>{
    journal.prepare.and.rejectWith(Error('quota')); await store.execute(command());
    expect(api.post).not.toHaveBeenCalled(); expect(store.canWrite()).toBeFalse();
  });
  it('serializes synchronous double submissions before creating a second journal intent',async()=>{
    const c=command(); await Promise.all([store.execute(c),store.execute(command())]);
    expect(journal.prepare.calls.count()).toBe(1); expect(api.post.calls.count()).toBe(1);
  });
  it('receipt does not release the block if authoritative refresh fails',async()=>{
    api.refresh.and.returnValue(of(false)); const result=await store.execute(command());
    expect(result.outcome).toBe(CommandOutcome.Unknown); expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);
    expect(store.canWrite()).toBeFalse(); expect(store.notice()).toContain('refresh');
  });
  it('NotFound conserves uncertainty and consult never posts again',async()=>{
    api.post.and.returnValue(of(null)); await store.execute(command());
    api.receipt.and.returnValue(of({code:404,traceId:'test',data:{outcome:CommandOutcome.NotFound.wire,failure:null}}));
    await store.consult(); await store.consult();
    expect(api.post.calls.count()).toBe(1); expect(store.pending()).not.toBeNull(); expect(store.canWrite()).toBeFalse();
  });
  it('same actor with a new session recovers only through GET',async()=>{
    api.post.and.returnValue(of(null)); const c=command(); await store.execute(c);
    session.generation.set(2);session.changed.next(); TestBed.tick();await settle();
    expect(api.receipt).toHaveBeenCalledWith(c);expect(api.post.calls.count()).toBe(1);expect(entries[0].phase).toBe(JournalPhase.Resolved);
  });
  it('different actor sees a neutral block and never reads receipt or adopts payload',async()=>{
    api.post.and.returnValue(of(null));await store.execute(command());session.staff.set({id:8});session.generation.set(2);session.changed.next();TestBed.tick();await settle();
    expect(store.pending()).toBeNull();expect(store.canWrite()).toBeFalse();expect(api.receipt).not.toHaveBeenCalled();expect(entries.length).toBe(1);
  });
  it('revoked permission prevents recovery and POST',async()=>{
    api.post.and.returnValue(of(null));await store.execute(command());session.can=()=>false;
    await store.consult();await store.execute(command());expect(api.receipt).not.toHaveBeenCalled();expect(api.post.calls.count()).toBe(1);
  });
  it('changed context during preflight prevents journal and POST',async()=>{
    api.context.and.returnValue(of(envelope({state:'Available',context:{...context,deviceId:'different'}})));
    await store.execute(command());expect(journal.prepare).not.toHaveBeenCalled();expect(api.post).not.toHaveBeenCalled();expect(store.canWrite()).toBeFalse();
  });
  for(const state of [AccountingLifecycleState.Unknown,AccountingLifecycleState.PreActivation,AccountingLifecycleState.Paused]){
    it('blocks lifecycle '+state.label,async()=>{
      api.lifecycle.and.returnValue(of(envelope({state:state.wire,contractVersion:'V2',activationAt:state===AccountingLifecycleState.PreActivation?null:'2026-10-07T12:00:00Z',observedAt:'2026-10-08T12:00:00Z'})));
      await store.execute(command());expect(api.post).not.toHaveBeenCalled();expect(store.canWrite()).toBeFalse();
    });
  }
  it('corrupt evidence remains quarantined and blocks hydrate',async()=>{
    corrupt=true;session.generation.set(2);session.changed.next();TestBed.tick();await settle();expect(store.canWrite()).toBeFalse();expect(store.notice()).toContain('cuarentena');expect(api.post).not.toHaveBeenCalled();
  });
  it('late POST response cannot resolve evidence after actor change',async()=>{
    const response=new Subject<unknown>();api.post.and.returnValue(response);const c=command();const result=store.execute(c);await settle();
    session.staff.set({id:8});session.generation.set(2);session.changed.next();TestBed.tick();await settle();
    response.next(commandEnvelope(c.commandId));response.complete();await result;
    expect(api.refresh).not.toHaveBeenCalled();expect(entries[0].phase).toBe(JournalPhase.AwaitingReceipt);expect(store.pending()).toBeNull();
  });
  it('shares concurrent GET recovery for one family and command',async()=>{
    api.post.and.returnValue(of(null));const c=command();await store.execute(c);
    const response=new Subject<unknown>();api.receipt.and.returnValue(response);const one=store.consult();const two=store.consult();
    expect(api.receipt.calls.count()).toBe(1);response.next(commandEnvelope(c.commandId));await Promise.all([one,two]);expect(api.post.calls.count()).toBe(1);
  });
  it('pre-journal reference denial prevents another reversal intent or POST',async()=>{
    api.references.and.returnValue(of(false));
    await store.execute(AccountingCommand.create(AccountingCommandKind.Reverse,{originalPaymentId:41,reason:'refund',evidenceRef:'ref'},41));
    expect(journal.prepare).not.toHaveBeenCalled();expect(api.post).not.toHaveBeenCalled();
  });
  it('expense receipt remains awaiting authoritative evidence with no resolved journal',async()=>{
    api.references.and.returnValue(of(2));api.post.and.callFake((c:AccountingCommand)=>of(commandEnvelope(c.commandId,{expenseId:5,settlementId:6})));api.refresh.and.returnValue(of(false));
    const c=AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,amount:'10',operation:ExpenseOperation.AccrueAndSettle.wire,category:'supplies'});
    await store.execute(c);expect(entries[0].phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);expect(store.canWrite()).toBeFalse();expect(store.notice()).toContain('T08-D');
  });
  it('quarantines a sale receipt with a syntactically valid mismatched hash',async()=>{
    const identity={...context,saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
    const c=SaleCommand.create(SaleCommandKind.Commit,identity,2,{reason:'frozen'});api.references.and.returnValue(of(2));
    const expected=await SaleAdmissionFingerprint.hash(c,7);
    api.post.and.returnValue(of(envelope({outcome:SaleAdmissionOutcome.Accepted.wire,failure:null,receipt:{...identity,commandId:c.commandId,kind:c.kind.wire,actorId:7,cashSessionId:2,payloadHash:'f'.repeat(64),intentId:1,outboxId:2,acceptedAt:'2026-10-08T00:00:00Z'}})));
    await store.execute(c);expect(entries[0].expectedPayloadHash).toBe(expected);expect(entries[0].phase).toBe(JournalPhase.Quarantined);
    expect(api.refresh).not.toHaveBeenCalled();expect(store.canWrite()).toBeFalse();await store.consult();expect(api.receipt).not.toHaveBeenCalled();
  });
});
