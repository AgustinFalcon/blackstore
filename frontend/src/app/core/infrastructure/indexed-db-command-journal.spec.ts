import { TestBed } from '@angular/core/testing';
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
  for(const kind of [SaleCommandKind.Reserve,SaleCommandKind.Commit,SaleCommandKind.Release])for(const phase of [JournalPhase.Prepared,JournalPhase.AwaitingReceipt,JournalPhase.ReceiptVerifiedAwaitingRefresh]){
    it(`rehydrates historical V1 ${kind.wire} in ${phase.wire} without route rewrite`,async()=>{
      const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
      const fields=kind===SaleCommandKind.Reserve?{cashSessionId:2,variantId:'a',quantity:1,expectedPriceVersion:'P1',sku:'SKU',productName:'Artículo',originalUnitPrice:'1.00',discountAmount:'0.00'}:{};
      const command=SaleCommand.create(kind,identity,2,fields),saved:JournalEntry={version:1,scope:{origin:location.origin,clientInstanceId:identity.clientInstanceId,deviceId:identity.deviceId,terminalId:10},
        family:JournalFamily.Sale,command,actorId:7,cashSessionId:2,phase,tabId:crypto.randomUUID(),expectedPayloadHash:await SaleAdmissionFingerprint.hash(command,7),fingerprintVersion:SaleFingerprintVersion.AdmissionV1};
      const raw=JournalDecoder.encode(saved) as Record<string,unknown>;expect('admissionRoute' in raw).toBeFalse();
      const decoded=JournalDecoder.decode(raw)!;expect(decoded.command.commandId).toBe(command.commandId);expect(decoded.phase).toBe(phase);
      expect(decoded.expectedPayloadHash).toBe(saved.expectedPayloadHash);expect(decoded.admissionRoute).toBe(command.route);
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
    await journal.prepare(saved);expect((await journal.list()).entries[0].command.body).toEqual(command.body);
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
