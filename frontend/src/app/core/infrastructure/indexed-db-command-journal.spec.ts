import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { PosContextState } from '../domain/pos-execution-context';
import { SessionState } from '../domain/session-types';
import { CommandRuntimeStore } from '../services/command-runtime.store';
import { SessionStore } from '../services/session.store';
import { FOREGROUND_REFRESH } from './foreground-refresh';
import { AccountingCommand,AccountingCommandKind } from '../domain/accounting-command';
import { JournalEntry,JournalFamily,JournalPhase } from '../domain/command-journal';
import { IndexedDbCommandJournal,JOURNAL_DATABASE,JournalDecoder } from './indexed-db-command-journal';
import {SaleAdmissionRoute,SaleCommand,SaleCommandKind,SaleFingerprintVersion,SaleAdmissionOutcome,SaleCommandFailure,SaleLineFailureCode} from '../domain/sale-command';
import {SaleAdmissionFingerprint,SaleAdmissionV2Fingerprint} from './sale-admission-fingerprint';
describe('untrusted journal decoder and transactional IndexedDB claim',()=>{
  const rejectedCommand=()=>SaleCommand.createMultilineReserve({clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'sale',operationId:'33333333-3333-3333-3333-333333333333'},2,[{variantId:'a',quantity:1,sku:'SKU',productName:'Artículo',expectedPriceVersion:'P1',originalUnitPrice:'1.00',discountAmount:'0.00'}]);
  const rejection=()=>({outcome:SaleAdmissionOutcome.Rejected,receipt:null,failure:SaleCommandFailure.Validation,lineFailures:[{variantId:'a',code:SaleLineFailureCode.PriceVersionMismatch}]});

  const entry=():JournalEntry=>({version:1,scope:{origin:location.origin,clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10},
    family:JournalFamily.Accounting,command:AccountingCommand.create(AccountingCommandKind.Open,{terminalId:10,cashierId:7,openingCash:'0'}),actorId:7,cashSessionId:null,phase:JournalPhase.Prepared,tabId:crypto.randomUUID()});
  let dbName:string;let journal:IndexedDbCommandJournal;
  beforeEach(()=>{dbName='blackstore-test-'+crypto.randomUUID();TestBed.configureTestingModule({providers:[{provide:JOURNAL_DATABASE,useValue:dbName}]});journal=TestBed.inject(IndexedDbCommandJournal);});
  afterEach(async()=>{await new Promise<void>((resolve,reject)=>{const r=indexedDB.deleteDatabase(dbName);r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error);});});
  it('persists terminal rejection atomically and never reopens it from a stale tab',async()=>{
    const command=rejectedCommand(),saved:JournalEntry={...entry(),version:2,family:JournalFamily.Sale,command,cashSessionId:2,
      expectedPayloadHash:await new SaleAdmissionV2Fingerprint().hash(command,7),fingerprintVersion:SaleFingerprintVersion.AdmissionV2,admissionRoute:SaleAdmissionRoute.ReserveV3};
    await journal.prepare(saved);await journal.transition(saved,JournalPhase.AwaitingReceipt);
    await expectAsync(journal.transition(saved,JournalPhase.Rejected)).toBeRejected();
    await expectAsync(journal.resolveRejected({...saved,actorId:8},rejection())).toBeRejected();
    await expectAsync(journal.resolveRejected(saved,{...rejection(),lineFailures:[]})).toBeRejected();
    await journal.resolveRejected(saved,rejection());
    const next=entry();await journal.prepare(next);
    await journal.transition(saved,JournalPhase.ReceiptVerifiedAwaitingRefresh);
    await journal.resolveRejected(saved,rejection());
    const snapshot=await journal.list();expect(snapshot.entries.find(e=>e.command.commandId===command.commandId)?.phase).toBe(JournalPhase.Rejected);
    expect(snapshot.entries.filter(e=>!e.phase.isTerminal).map(e=>e.command.commandId)).toEqual([next.command.commandId]);
    const reopened=TestBed.runInInjectionContext(()=>new IndexedDbCommandJournal());const durable=(await reopened.list()).entries.find(e=>e.command.commandId===command.commandId)!;
    expect(durable.rejection?.lineFailures).toEqual(rejection().lineFailures);
    expect(durable.command.body).toEqual(command.body);expect(durable.expectedPayloadHash).toBe(saved.expectedPayloadHash);
    const encoded=JournalDecoder.encode(durable) as any;
    expect(JournalDecoder.decode({...encoded,rejection:{...encoded.rejection,lineFailures:[]}})).toBeNull();
    expect(JournalDecoder.decode({...encoded,rejection:{...encoded.rejection,lineFailures:[{variantId:'foreign',code:SaleLineFailureCode.PriceMismatch.wire}]}})).toBeNull();
    expect(JournalDecoder.decode({...encoded,rejection:{...encoded.rejection,lineFailures:[{variantId:'a',code:'Future'}]}})).toBeNull();
    expect(JournalDecoder.decode({...encoded,phase:JournalPhase.Resolved.wire})).toBeNull();
  });
  it('round trips frozen commands and excludes credentials',()=>{
    const saved=entry();const decoded=JournalDecoder.decode(JournalDecoder.encode(saved))!;
    expect(decoded.command.kind).toBe(saved.command.kind);expect(Object.isFrozen(decoded.command.body)).toBeTrue();
    expect(JSON.stringify(JournalDecoder.encode(saved))).not.toContain('csrf');
  });
  for(const overrides of [{version:2},{family:'future'},{phase:'future'},{actorId:0},{tabId:'bad'},{cashSessionId:-1},{scope:null}]){
    it('quarantines malformed persisted evidence '+JSON.stringify(overrides),()=>expect(JournalDecoder.decode({...JournalDecoder.encode(entry()) as object,...overrides})).toBeNull());
  }
  it('rejects oversized, nested, secret and mismatched bodies',()=>{
    const saved=JournalDecoder.encode(entry()) as any;
    for(const body of [{...saved.command.body,password:'secret'},{...saved.command.body,reason:{a:1}},{...saved.command.body,reason:'x'.repeat(32768)},{...saved.command.body,commandId:crypto.randomUUID()}])
      expect(JournalDecoder.decode({...saved,command:{...saved.command,body}})).toBeNull();
  });
  it('two tabs racing prepare commit exactly one claim and preserve loser evidence',async()=>{
    const second=TestBed.runInInjectionContext(()=>new IndexedDbCommandJournal());
    const outcomes=await Promise.allSettled([journal.prepare(entry()),second.prepare(entry())]);
    expect(outcomes.filter(o=>o.status==='fulfilled').length).toBe(1);expect((await journal.list()).entries.length).toBe(1);
  });
  it('does not release a claim before the resolved transaction completes',async()=>{
    const first=entry();await journal.prepare(first);await expectAsync(journal.prepare(entry())).toBeRejected();
    await journal.transition(first,JournalPhase.ReceiptVerifiedAwaitingRefresh);await expectAsync(journal.prepare(entry())).toBeRejected();
    await journal.transition(first,JournalPhase.Resolved);await journal.prepare(entry());expect((await journal.list()).entries.length).toBe(2);
  });
  it('keeps A terminal after B claim and stale A receipt transitions from another tab',async()=>{
    const other=TestBed.runInInjectionContext(()=>new IndexedDbCommandJournal()),a=entry(),b=entry();
    await journal.prepare(a);await other.transition(a,JournalPhase.ReceiptVerifiedAwaitingRefresh);await other.transition(a,JournalPhase.Resolved);
    await other.prepare(b);
    await journal.transition(a,JournalPhase.ReceiptVerifiedAwaitingRefresh);await journal.transition(a,JournalPhase.AwaitingReceipt);await journal.transition(a,JournalPhase.Resolved);
    const snapshot=await journal.list();expect(snapshot.quarantined).toBeFalse();
    expect(snapshot.entries.find(v=>v.command.commandId===a.command.commandId)?.phase).toBe(JournalPhase.Resolved);
    expect(snapshot.entries.filter(v=>!v.phase.isTerminal).map(v=>v.command.commandId)).toEqual([b.command.commandId]);
  });
  it('rejects skipping receipt verification atomically and preserves stored phase',async()=>{
    const a=entry();await journal.prepare(a);await expectAsync(journal.transition(a,JournalPhase.Resolved)).toBeRejected();
    expect((await journal.list()).entries[0].phase).toBe(JournalPhase.Prepared);
    await journal.transition(a,JournalPhase.AwaitingReceipt);await journal.transition(a,JournalPhase.Prepared);
    expect((await journal.list()).entries[0].phase).toBe(JournalPhase.AwaitingReceipt);
    await expectAsync(journal.transition(a,JournalPhase.Unknown)).toBeRejected();
  });
  it('quarantine transition is terminal idempotent storage but remains fail-closed to readers',async()=>{
    const a=entry();await journal.prepare(a);await journal.transition(a,JournalPhase.Quarantined);await journal.transition(a,JournalPhase.Quarantined);
    await expectAsync(journal.transition(a,JournalPhase.Resolved)).toBeRejected();const snapshot=await journal.list();
    expect(snapshot.quarantined).toBeTrue();expect(snapshot.entries).toEqual([]);await expectAsync(journal.prepare(entry())).toBeRejected();
  });
  it('quarantines syntactically valid altered sale payload without deleting evidence',async()=>{
    const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
    const command=SaleCommand.create(SaleCommandKind.Commit,identity,2,{reason:'original'});
    const saved:JournalEntry={...entry(),command,family:JournalFamily.Sale,cashSessionId:2,expectedPayloadHash:await SaleAdmissionFingerprint.hash(command,7),fingerprintVersion:SaleAdmissionFingerprint.version};
    await journal.prepare(saved);
    const altered=JournalDecoder.encode(saved) as any;altered.command.body={...altered.command.body,reason:'alterado'};
    expect(JournalDecoder.decode(altered)).not.toBeNull();
    await new Promise<void>((resolve,reject)=>{
      const open=indexedDB.open(dbName,1);open.onerror=()=>reject(open.error);open.onsuccess=()=>{
        const db=open.result,tx=db.transaction('intents','readwrite');tx.objectStore('intents').put(altered);tx.oncomplete=()=>{db.close();resolve();};tx.onabort=()=>{db.close();reject(tx.error);};
      };
    });
    const read=await journal.list();expect(read.quarantined).toBeTrue();expect(read.entries).toEqual([]);await expectAsync(journal.prepare(entry())).toBeRejected();
  });
  it('rejects preparing a wrong but well-formed expected hash before the claim',async()=>{
    const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
    const command=SaleCommand.create(SaleCommandKind.Release,identity,2,{});
    await expectAsync(journal.prepare({...entry(),command,family:JournalFamily.Sale,cashSessionId:2,expectedPayloadHash:'f'.repeat(64),fingerprintVersion:SaleAdmissionFingerprint.version})).toBeRejected();
    expect((await journal.list()).entries).toEqual([]);
  });
  // Historical scalar envelopes are seeded directly, never through today's encoder/prepare.
  const historicalCases = [
    {kind:SaleCommandKind.Reserve,hash:'a3e90e5c5620c40fa5940fd82618c25e43614767e357ff7989765ce1a2e10959'},
    {kind:SaleCommandKind.Commit,hash:'55e7f3f0309dc766d2d29ac7c35be90ee085dde73eb1942b2964997b24e2aade'},
    {kind:SaleCommandKind.Release,hash:'fafcb7effd2c252c05dce0afd42643eef8f0e44b6cdd67357ebd6667b7d9dd31'},
  ];
  for(const {kind,hash} of historicalCases)for(const phase of [JournalPhase.Prepared,JournalPhase.AwaitingReceipt,JournalPhase.ReceiptVerifiedAwaitingRefresh]){
    it(`upgrades persisted historical V1 ${kind.wire} in ${phase.wire} through hydrate, GET v2 and reload without rewriting evidence`,async()=>{
      const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
      const fields=kind===SaleCommandKind.Reserve?{cashSessionId:2,variantId:'a',quantity:1,expectedPriceVersion:'P1',sku:'SKU',productName:'Artículo',originalUnitPrice:'1.00',discountAmount:'0.00'}:{};
      const commandId='44444444-4444-4444-4444-444444444444',context={clientInstanceId:identity.clientInstanceId,deviceId:identity.deviceId,terminalId:10};
      const body={...fields,...identity,commandId,reason:null};
      const raw={version:1,scope:{origin:location.origin,...context},family:JournalFamily.Sale.wire,
        command:{kind:kind.wire,commandId,identity,cashSessionId:2,body},actorId:7,cashSessionId:2,
        phase:phase.wire,tabId:'55555555-5555-5555-5555-555555555555',expectedPayloadHash:hash,fingerprintVersion:SaleFingerprintVersion.AdmissionV1.wire};
      const original=JSON.stringify(raw);
      const stored=async(write=false):Promise<unknown>=>new Promise((resolve,reject)=>{
        const open=indexedDB.open(dbName,1);
        open.onupgradeneeded=()=>open.result.createObjectStore('intents',{keyPath:'command.commandId'});
        open.onerror=()=>reject(open.error);open.onsuccess=()=>{
          const db=open.result,tx=db.transaction('intents',write?'readwrite':'readonly');
          const request=write?tx.objectStore('intents').add(raw):tx.objectStore('intents').get(commandId);
          let result:unknown;request.onsuccess=()=>{result=request.result;};
          tx.oncomplete=()=>{db.close();resolve(result);};tx.onabort=()=>{db.close();reject(tx.error);};
        };
      });
      await stored(true);
      for(let reload=0;reload<2;reload++){
        TestBed.resetTestingModule();
        const session={staff:signal({id:7}),state:signal(SessionState.Authenticated),generation:signal(1),changed:new Subject<void>(),can:()=>true};
        TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:JOURNAL_DATABASE,useValue:dbName},
          {provide:SessionStore,useValue:session},{provide:FOREGROUND_REFRESH,useValue:{subscribe:()=>()=>{}}}]});
        const http=TestBed.inject(HttpTestingController),runtime=TestBed.inject(CommandRuntimeStore);
        const envelope=(data:unknown)=>({code:200,traceId:'historical-upgrade',data});
        TestBed.tick();
        http.expectOne(`${ACCOUNTING_API_BASE}/pos/context`).flush(envelope({state:PosContextState.Available.wire,context}));
        http.expectOne(`${ACCOUNTING_API_BASE}/accounting/runtime`).flush(envelope({state:AccountingLifecycleState.Active.wire,contractVersion:'V2',activationAt:'2026-10-07T00:00:00Z',observedAt:'2026-10-08T00:00:00Z'}));
        for(let wait=0;wait<100&&!runtime.hydrated();wait++)await new Promise<void>(resolve=>setTimeout(resolve,5));
        expect(runtime.hydrated()).toBeTrue();expect(runtime.neutralBlock()).toBeFalse();expect(runtime.canWrite()).toBeFalse();
        const recovered=runtime.pending()!;
        expect(recovered.phase).toBe(phase);expect(recovered.version).toBe(1);expect(recovered.expectedPayloadHash).toBe(hash);
        expect(recovered.fingerprintVersion).toBe(SaleFingerprintVersion.AdmissionV1);
        expect(recovered.command.body).toEqual(body);expect((recovered.command as SaleCommand).identity).toEqual(identity);
        expect(recovered.admissionRoute).toBe(kind===SaleCommandKind.Reserve?SaleAdmissionRoute.ReserveV2:kind===SaleCommandKind.Commit?SaleAdmissionRoute.CommitV2:SaleAdmissionRoute.ReleaseV2);
        const get=http.expectOne(`${ACCOUNTING_API_BASE}/sales/commands/${commandId}`);expect(get.request.method).toBe('GET');
        const recovery=runtime.consult();
        get.flush(null); // An uncertain receipt must retain every historical byte and block writes.
        await recovery;
        http.expectNone(request=>request.method==='POST');http.verify();
        expect(JSON.stringify(await stored())).toBe(original);
        const reopened=TestBed.runInInjectionContext(()=>new IndexedDbCommandJournal()),snapshot=await reopened.list();
        expect(snapshot.quarantined).toBeFalse();expect(snapshot.entries.length).toBe(1);expect(snapshot.entries[0].phase).toBe(phase);
      }
    });
  }
  for(const kind of [SaleCommandKind.Commit,SaleCommandKind.Release]){
    it(`rehydrates V1 ${kind.wire} for an opaque V3 sale reference without trimming it`,async()=>{
      const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'\u00a0verified-device\u00a0',saleId:'\u00a0opaque-sale\u00a0',operationId:'33333333-3333-3333-3333-333333333333'};
      const command=SaleCommand.create(kind,identity,2,{}),saved:JournalEntry={version:1,scope:{origin:location.origin,clientInstanceId:identity.clientInstanceId,deviceId:identity.deviceId,terminalId:10},
        family:JournalFamily.Sale,command,actorId:7,cashSessionId:2,phase:JournalPhase.Prepared,tabId:crypto.randomUUID(),expectedPayloadHash:await SaleAdmissionFingerprint.hash(command,7),fingerprintVersion:SaleFingerprintVersion.AdmissionV1};
      const decoded=JournalDecoder.decode(JournalDecoder.encode(saved))!;
      expect(decoded).not.toBeNull();expect(decoded.command instanceof SaleCommand).toBeTrue();
      const restored=decoded.command as SaleCommand;expect(restored.identity.saleId).toBe(identity.saleId);expect(restored.identity.deviceId).toBe(identity.deviceId);
    });
  }
  it('round trips a maximum multiline V2 envelope with opaque saleId and deep frozen lines',async()=>{
    const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'opaque-sale',operationId:'33333333-3333-3333-3333-333333333333'};
    const lines=Array.from({length:100},(_,index)=>({variantId:`v${index}`.padEnd(80,'x'),quantity:1,expectedPriceVersion:`P${index}`,
      sku:`SKU${index}`,productName:`Artículo ${index}\u0001`.padEnd(250,'n'),originalUnitPrice:'0.01',discountAmount:'0.00'}));
    const command=SaleCommand.createMultilineReserve(identity,2,lines,'r'.repeat(500)),codec=new SaleAdmissionV2Fingerprint();
    const saved:JournalEntry={version:2,scope:{origin:location.origin,clientInstanceId:identity.clientInstanceId,deviceId:identity.deviceId,terminalId:10},
      family:JournalFamily.Sale,command,actorId:7,cashSessionId:2,phase:JournalPhase.Prepared,tabId:crypto.randomUUID(),
      expectedPayloadHash:await codec.hash(command,7),fingerprintVersion:SaleFingerprintVersion.AdmissionV2,admissionRoute:SaleAdmissionRoute.ReserveV3};
    const encoded=JournalDecoder.encode(saved),decoded=JournalDecoder.decode(encoded)!;
    expect(new TextEncoder().encode(JSON.stringify(encoded)).length).toBeLessThanOrEqual(524288);
    expect(decoded.version).toBe(2);expect(decoded.admissionRoute).toBe(SaleAdmissionRoute.ReserveV3);expect(decoded.command.body).toEqual(command.body);
    expect(Object.isFrozen(decoded.command.body['lines'])).toBeTrue();expect(Object.isFrozen((decoded.command.body['lines'] as readonly object[])[0])).toBeTrue();
    await journal.prepare(saved);
    const reopened=TestBed.runInInjectionContext(()=>new IndexedDbCommandJournal()),snapshot=await reopened.list();
    expect(snapshot.quarantined).toBeFalse();expect(snapshot.entries.length).toBe(1);
    expect(snapshot.entries[0].command.body).toEqual(command.body);expect(snapshot.entries[0].expectedPayloadHash).toBe(saved.expectedPayloadHash);
    expect(snapshot.entries[0].fingerprintVersion).toBe(SaleFingerprintVersion.AdmissionV2);expect(snapshot.entries[0].admissionRoute).toBe(SaleAdmissionRoute.ReserveV3);
  });
  it('quarantines V2 nested extras, incompatible routes and malformed Unicode without weakening V1',async()=>{
    const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'sale',operationId:'33333333-3333-3333-3333-333333333333'};
    const command=SaleCommand.createMultilineReserve(identity,2,[{variantId:'a',quantity:1,expectedPriceVersion:'P1',sku:'SKU',productName:'Artículo',originalUnitPrice:'1.00',discountAmount:'0.00'}]);
    const codec=new SaleAdmissionV2Fingerprint(),saved:JournalEntry={version:2,scope:{origin:location.origin,clientInstanceId:identity.clientInstanceId,deviceId:identity.deviceId,terminalId:10},
      family:JournalFamily.Sale,command,actorId:7,cashSessionId:2,phase:JournalPhase.Prepared,tabId:crypto.randomUUID(),expectedPayloadHash:await codec.hash(command,7),
      fingerprintVersion:SaleFingerprintVersion.AdmissionV2,admissionRoute:SaleAdmissionRoute.ReserveV3};
    const encoded=JournalDecoder.encode(saved) as any;
    expect(JournalDecoder.decode({...encoded,admissionRoute:SaleAdmissionRoute.ReserveV2.wire})).toBeNull();
    expect(JournalDecoder.decode({...encoded,command:{...encoded.command,body:{...encoded.command.body,secret:'x'}}})).toBeNull();
    const line={...encoded.command.body.lines[0],productName:'bad\ud800'};
    expect(JournalDecoder.decode({...encoded,command:{...encoded.command,body:{...encoded.command.body,lines:[line]}}})).toBeNull();
  });
});
