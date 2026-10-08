import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject,of } from 'rxjs';
import { AccountingCommand,AccountingCommandKind } from '../domain/accounting-command';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { JournalEntry,JournalFamily,JournalPhase } from '../domain/command-journal';
import { SessionState } from '../domain/session-types';
import { IndexedDbCommandJournal,JOURNAL_DATABASE } from '../infrastructure/indexed-db-command-journal';
import { CommandHttp } from '../infrastructure/command-http';
import { commandEnvelope } from '../infrastructure/accounting-command-test-helper';
import { FOREGROUND_REFRESH } from '../infrastructure/foreground-refresh';
import { CommandRuntimeStore } from './command-runtime.store';
import { SessionStore } from './session.store';
describe('cross-tab durable recovery cannot reopen a resolved command',()=>{
  it('A resolves, B claims, late A record stays terminal and hydrate recovers only B through GET',async()=>{
    const dbName='blackstore-tab-race-'+crypto.randomUUID(),context={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10};
    const entry=():JournalEntry=>({version:1,scope:{origin:location.origin,...context},family:JournalFamily.Accounting,
      command:AccountingCommand.create(AccountingCommandKind.Open,{terminalId:10,cashierId:7,openingCash:'0'}),actorId:7,cashSessionId:null,phase:JournalPhase.Prepared,tabId:crypto.randomUUID()});
    const a=entry(),b=entry(),lateA=new Subject<unknown>(),pendingB=new Subject<unknown>();
    const envelope=(data:unknown)=>({code:200,traceId:'test',data});
    const session={staff:signal({id:7}),state:signal(SessionState.Authenticated),generation:signal(1),changed:new Subject<void>(),can:()=>true};
    const api={context:jasmine.createSpy().and.returnValue(of(envelope({state:'Available',context}))),
      lifecycle:jasmine.createSpy().and.returnValue(of(envelope({state:AccountingLifecycleState.Active.wire,contractVersion:'V2',activationAt:'2026-10-07T00:00:00Z',observedAt:'2026-10-08T00:00:00Z'}))),
      receipt:jasmine.createSpy().and.callFake((c:AccountingCommand)=>c.commandId===a.command.commandId?lateA:pendingB),
      refresh:jasmine.createSpy().and.returnValue(of(true)),post:jasmine.createSpy()};
    TestBed.configureTestingModule({providers:[{provide:JOURNAL_DATABASE,useValue:dbName},{provide:SessionStore,useValue:session},
      {provide:CommandHttp,useValue:api},{provide:FOREGROUND_REFRESH,useValue:{subscribe:()=>()=>{}}}]});
    const journal=TestBed.inject(IndexedDbCommandJournal),otherTab=TestBed.runInInjectionContext(()=>new IndexedDbCommandJournal());
    const wait=async(predicate:()=>boolean)=>{for(let i=0;i<100&&!predicate();i++)await new Promise<void>(resolve=>setTimeout(resolve,5));expect(predicate()).toBeTrue();};
    try{
      await journal.prepare(a);const runtime=TestBed.inject(CommandRuntimeStore);TestBed.tick();await wait(()=>api.receipt.calls.count()===1);
      expect(runtime.pending()?.command.commandId).toBe(a.command.commandId);
      await otherTab.transition(a,JournalPhase.ReceiptVerifiedAwaitingRefresh);await otherTab.transition(a,JournalPhase.Resolved);await otherTab.prepare(b);
      lateA.next(commandEnvelope(a.command.commandId));await wait(()=>api.refresh.calls.count()===1 && runtime.pending()===null);
      const snapshot=await journal.list();expect(snapshot.quarantined).toBeFalse();
      expect(snapshot.entries.find(v=>v.command.commandId===a.command.commandId)?.phase).toBe(JournalPhase.Resolved);
      expect(snapshot.entries.filter(v=>v.phase!==JournalPhase.Resolved).map(v=>v.command.commandId)).toEqual([b.command.commandId]);
      runtime.refresh();await wait(()=>api.receipt.calls.count()===2);
      expect(runtime.hydrated()).toBeTrue();expect(runtime.neutralBlock()).toBeFalse();expect(runtime.pending()?.command.commandId).toBe(b.command.commandId);
      expect(api.receipt.calls.mostRecent().args[0].commandId).toBe(b.command.commandId);expect(api.post).not.toHaveBeenCalled();
      pendingB.next(null);await wait(()=>runtime.pending()?.command.commandId===b.command.commandId);
    }finally{
      TestBed.resetTestingModule();await new Promise<void>((resolve,reject)=>{const r=indexedDB.deleteDatabase(dbName);r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error);});
    }
  });
});
