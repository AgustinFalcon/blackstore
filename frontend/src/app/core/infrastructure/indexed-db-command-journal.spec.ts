import { TestBed } from '@angular/core/testing';
import { AccountingCommand,AccountingCommandKind } from '../domain/accounting-command';
import { JournalEntry,JournalFamily,JournalPhase } from '../domain/command-journal';
import { IndexedDbCommandJournal,JOURNAL_DATABASE,JournalDecoder } from './indexed-db-command-journal';
import {SaleCommand,SaleCommandKind} from '../domain/sale-command';
import {SaleAdmissionFingerprint} from './sale-admission-fingerprint';
describe('untrusted journal decoder and transactional IndexedDB claim',()=>{
  const entry=():JournalEntry=>({version:1,scope:{origin:location.origin,clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',terminalId:10},
    family:JournalFamily.Accounting,command:AccountingCommand.create(AccountingCommandKind.Open,{terminalId:10,cashierId:7,openingCash:'0'}),actorId:7,cashSessionId:null,phase:JournalPhase.Prepared,tabId:crypto.randomUUID()});
  let dbName:string;let journal:IndexedDbCommandJournal;
  beforeEach(()=>{dbName='blackstore-test-'+crypto.randomUUID();TestBed.configureTestingModule({providers:[{provide:JOURNAL_DATABASE,useValue:dbName}]});journal=TestBed.inject(IndexedDbCommandJournal);});
  afterEach(async()=>{await new Promise<void>((resolve,reject)=>{const r=indexedDB.deleteDatabase(dbName);r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error);});});
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
});
