import { Injectable, InjectionToken, inject } from '@angular/core';
import { AccountingCommand, AccountingCommandKind, ExpenseOperation } from '../domain/accounting-command';
import { CommandJournal, JournalEntry, JournalFamily, JournalPhase, JournalSnapshot,sameScope } from '../domain/command-journal';
import { SaleAdmissionRoute, SaleCommand, SaleCommandKind,SaleFingerprintVersion } from '../domain/sale-command';
import { SaleTextV2 } from '../domain/sale-text-v2';
import { PosWireMapper } from './pos-wire-mapper';
import { TicketMoney } from '../domain/ticket-transition';
import { PaymentMethod } from '../domain/pos-types';
import { SaleFingerprintRegistry } from './sale-admission-fingerprint';

/** Storage is untrusted evidence. This decoder is the only storage-to-domain boundary. */
export class JournalDecoder {
  static decode(raw: unknown,allowQuarantined=false): JournalEntry | null {
    try {
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
      const r = raw as Record<string, unknown>; const scope = r['scope'] as Record<string, unknown>;
      const command = r['command'] as Record<string, unknown>; const body = command?.['body'] as Record<string, unknown>;
      const family = JournalFamily.fromWire(r['family']); const phase = JournalPhase.fromWire(r['phase']);
      const id = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0;
      const serialized=JSON.stringify(raw);
      if ((r['version']===1&&serialized.length>32768)||(r['version']===2&&new TextEncoder().encode(serialized).length>524288)||![1,2].includes(r['version'] as number))return null;
      if (!scope || typeof scope['origin'] !== 'string' || !/^https?:\/\//.test(scope['origin']) ||
        new URL(scope['origin']).origin !== scope['origin'] || !PosWireMapper.uuid(scope['clientInstanceId']) || typeof scope['deviceId'] !== 'string' || !scope['deviceId'] || scope['deviceId'].length > 80 || !id(scope['terminalId']) ||
        !id(r['actorId']) || (r['cashSessionId'] !== null && !id(r['cashSessionId'])) || !PosWireMapper.uuid(r['tabId']) ||
        family === JournalFamily.Unknown || phase === JournalPhase.Unknown || phase === JournalPhase.Quarantined && !allowQuarantined ||
        !command || !PosWireMapper.uuid(command['commandId']) || !body || Array.isArray(body) || body['commandId'] !== command['commandId']) return null;
      if(r['version']===2)return this.decodeV2(r,scope,command,body,family,phase,allowQuarantined);
      // Historical V1 remains scalar and keeps its original character budget.
      const allowed = ['commandId','clientInstanceId','deviceId','saleId','operationId','cashSessionId','terminalId','cashierId','openingCash','declaredCash','amount','paymentMethod','reason','originalPaymentId','evidenceRef','operation','expenseId','category','variantId','quantity','expectedPriceVersion','sku','productName','originalUnitPrice','discountAmount'];
      if (Object.entries(body).some(([key,value]) => !allowed.includes(key) || (value !== null && !['string','number','boolean'].includes(typeof value)))) return null;
      const identityValid = (v2=false) => PosWireMapper.uuid(body['clientInstanceId']) && typeof body['saleId']==='string' && !!body['saleId'] && (body['saleId'] as string).length<=80 &&
        (v2?SaleTextV2.ref(body['saleId'] as string)===body['saleId']:body['saleId']===(body['saleId'] as string).trim()) && PosWireMapper.uuid(body['operationId']) &&
        body['clientInstanceId'] === scope['clientInstanceId'] && body['deviceId'] === scope['deviceId'];
      const money = (key:string,positive=false) => { const amount=TicketMoney.fromDecimal(body[key]);return !!amount && (positive ? amount.cents>0n : amount.cents>=0n); };
      const text = (key:string) => typeof body[key] === 'string' && !!(body[key] as string).trim();
      const frozen = Object.freeze({ ...body }); let decoded: AccountingCommand | SaleCommand;
      if (family === JournalFamily.Accounting) {
        if(scope['deviceId']!==scope['deviceId'].trim())return null;
        const kind = AccountingCommandKind.fromWire(command['kind']);
        if (kind === AccountingCommandKind.Unknown || (command['aggregateId'] != null && !id(command['aggregateId']))) return null;
        if (kind === AccountingCommandKind.Open && (body['terminalId'] !== scope['terminalId'] || !id(body['cashierId']) || !money('openingCash') || r['cashSessionId'] !== null)) return null;
        if (kind === AccountingCommandKind.Close && (r['cashSessionId'] !== command['aggregateId'] || !id(r['cashSessionId']) || !money('declaredCash'))) return null;
        if (kind === AccountingCommandKind.Expense && (!id(r['cashSessionId']) || body['cashSessionId'] !== r['cashSessionId'] || ExpenseOperation.fromWire(body['operation']) === ExpenseOperation.Unknown ||
          (body['operation'] === ExpenseOperation.SettleExisting.wire ? !id(body['expenseId']) : !money('amount',true) || !text('category')))) return null;
        if (kind === AccountingCommandKind.Capture && (!id(r['cashSessionId']) || !identityValid() || !money('amount',true) || PaymentMethod.fromWire(body['paymentMethod']) === PaymentMethod.Unknown)) return null;
        if (kind === AccountingCommandKind.Reverse && (!id(r['cashSessionId']) || !identityValid() || !id(body['originalPaymentId']) || body['originalPaymentId'] !== command['aggregateId'] || !text('reason') || !text('evidenceRef'))) return null;
        decoded = AccountingCommand.fromJournal(kind, command['commandId'], frozen, command['aggregateId'] as number | undefined);
      } else {
        if(SaleFingerprintVersion.fromWire(r['fingerprintVersion'])!==SaleFingerprintVersion.AdmissionV1 || typeof r['expectedPayloadHash']!=='string' || !/^[0-9a-f]{64}$/.test(r['expectedPayloadHash']))return null;
        const kind = SaleCommandKind.fromWire(command['kind']);
        const identity = { clientInstanceId: body['clientInstanceId'], deviceId: body['deviceId'], saleId: body['saleId'], operationId: body['operationId'] };
        const terminalIdentity=kind===SaleCommandKind.Commit||kind===SaleCommandKind.Release;
        const ref=(value:unknown)=>typeof value==='string'&&!!value&&value.length<=80&&(terminalIdentity?SaleTextV2.ref(value)===value:value.trim()===value);
        if (kind === SaleCommandKind.Unknown || !id(r['cashSessionId']) || !PosWireMapper.uuid(identity.clientInstanceId) || !ref(identity.saleId) || !PosWireMapper.uuid(identity.operationId) ||
          !ref(identity.deviceId) || !identityValid(terminalIdentity) || command['cashSessionId'] !== r['cashSessionId']) return null;
        if (kind === SaleCommandKind.Reserve && (body['cashSessionId'] !== r['cashSessionId'] || !text('variantId') || !text('expectedPriceVersion') || !text('sku') || !text('productName') ||
          !id(body['quantity']) || !money('originalUnitPrice',true) || !money('discountAmount'))) return null;
        decoded = SaleCommand.fromJournal(kind, command['commandId'], identity as import('../domain/ticket-transition').TicketIdentity, r['cashSessionId'], frozen);
      }
      return Object.freeze({ version: 1, scope: Object.freeze({ ...scope }) as unknown as JournalEntry['scope'], command: decoded,
        family, phase, actorId: r['actorId'], cashSessionId: r['cashSessionId'] as number | null, tabId: r['tabId'] as string,
        ...(family===JournalFamily.Sale?{expectedPayloadHash:r['expectedPayloadHash'] as string,fingerprintVersion:SaleFingerprintVersion.fromWire(r['fingerprintVersion']),admissionRoute:decoded instanceof SaleCommand?decoded.route:undefined}:{}) });
    } catch { return null; }
  }
  private static decodeV2(r:Record<string,unknown>,scope:Record<string,unknown>,command:Record<string,unknown>,body:Record<string,unknown>,family:JournalFamily,phase:JournalPhase,allowQuarantined:boolean):JournalEntry|null {
    const exact=(value:Record<string,unknown>,keys:readonly string[])=>Object.keys(value).length===keys.length&&keys.every(key=>key in value);
    const id=(value:unknown):value is number=>typeof value==='number'&&Number.isSafeInteger(value)&&value>0;
    const envelope=['version','scope','family','command','actorId','cashSessionId','phase','tabId','expectedPayloadHash','fingerprintVersion','admissionRoute'];
    const commandKeys=['kind','commandId','identity','cashSessionId','body'];
    const bodyKeys=['commandId','clientInstanceId','deviceId','saleId','operationId','cashSessionId','lines','reason'];
    const lineKeys=['variantId','quantity','expectedPriceVersion','sku','productName','originalUnitPrice','discountAmount'];
    const identity=command['identity'] as Record<string,unknown>;
    const route=SaleAdmissionRoute.fromWire(r['admissionRoute']);
    if(!exact(r,envelope)||!exact(scope,['origin','clientInstanceId','deviceId','terminalId'])||family!==JournalFamily.Sale||
      phase===JournalPhase.Unknown||phase===JournalPhase.Quarantined&&!allowQuarantined||!exact(command,commandKeys)||!exact(body,bodyKeys)||
      !identity||!exact(identity,['clientInstanceId','deviceId','saleId','operationId'])||route!==SaleAdmissionRoute.ReserveV3||
      SaleFingerprintVersion.fromWire(r['fingerprintVersion'])!==SaleFingerprintVersion.AdmissionV2||!PosWireMapper.uuid(command['commandId'])||
      body['commandId']!==command['commandId']||command['kind']!==SaleCommandKind.Reserve.wire||!id(r['actorId'])||!id(r['cashSessionId'])||
      command['cashSessionId']!==r['cashSessionId']||!PosWireMapper.uuid(r['tabId'])||typeof r['expectedPayloadHash']!=='string'||!/^[0-9a-f]{64}$/.test(r['expectedPayloadHash']))return null;
    const candidate={clientInstanceId:body['clientInstanceId'],deviceId:body['deviceId'],saleId:body['saleId'],operationId:body['operationId']};
    if(!PosWireMapper.uuid(candidate.clientInstanceId)||!PosWireMapper.uuid(candidate.operationId)||typeof candidate.deviceId!=='string'||typeof candidate.saleId!=='string'||
      SaleTextV2.ref(candidate.deviceId)!==scope['deviceId']||SaleTextV2.ref(candidate.saleId)!==identity['saleId']||candidate.clientInstanceId!==scope['clientInstanceId']||
      candidate.clientInstanceId!==identity['clientInstanceId']||candidate.deviceId!==identity['deviceId']||candidate.operationId!==identity['operationId']||body['cashSessionId']!==r['cashSessionId'])return null;
    const lines=body['lines']; if(!Array.isArray(lines)||lines.length<1||lines.length>100)return null;
    const seen=new Set<string>();
    const frozenLines=Object.freeze(lines.map(rawLine=>{
      if(!rawLine||typeof rawLine!=='object'||Array.isArray(rawLine)||!exact(rawLine as Record<string,unknown>,lineKeys))throw new Error('Línea inválida');
      const line=rawLine as Record<string,unknown>,variantId=SaleTextV2.ref(String(line['variantId']));
      if(['variantId','expectedPriceVersion','sku','productName','originalUnitPrice','discountAmount'].some(key=>typeof line[key]!=='string')||
        seen.has(variantId)||!Number.isSafeInteger(line['quantity'])||(line['quantity'] as number)<1)throw new Error('Línea inválida');seen.add(variantId);
      const price=TicketMoney.fromDecimal(line['originalUnitPrice']),discount=TicketMoney.fromDecimal(line['discountAmount']);
      if(!price||price.cents<=0n||!discount||discount.cents!==0n)throw new Error('Dinero inválido');
      return Object.freeze({variantId,quantity:line['quantity'] as number,expectedPriceVersion:SaleTextV2.ref(String(line['expectedPriceVersion'])),
        sku:SaleTextV2.ref(String(line['sku'])),productName:SaleTextV2.name(String(line['productName'])),originalUnitPrice:price.decimal,discountAmount:discount.decimal});
    }));
    const frozenBody=Object.freeze({...body,reason:SaleTextV2.reason(body['reason'] as string|null),lines:frozenLines});
    const decoded=SaleCommand.fromJournal(SaleCommandKind.Reserve,command['commandId'] as string,Object.freeze(candidate) as import('../domain/ticket-transition').TicketIdentity,
      r['cashSessionId'] as number,frozenBody,route);
    return Object.freeze({version:2,scope:Object.freeze({...scope}) as unknown as JournalEntry['scope'],family,command:decoded,actorId:r['actorId'] as number,
      cashSessionId:r['cashSessionId'] as number,phase,tabId:r['tabId'] as string,expectedPayloadHash:r['expectedPayloadHash'] as string,
      fingerprintVersion:SaleFingerprintVersion.AdmissionV2,admissionRoute:route});
  }
  static encode(entry: JournalEntry): unknown {
    const {admissionRoute,...withoutRoute}=entry;
    const command=entry.command instanceof SaleCommand?{kind:entry.command.kind.wire,commandId:entry.command.commandId,identity:entry.command.identity,
      cashSessionId:entry.command.cashSessionId,body:entry.command.body}:{...entry.command,kind:entry.command.kind.wire};
    const base={...withoutRoute,family:entry.family.wire,phase:entry.phase.wire,fingerprintVersion:entry.fingerprintVersion?.wire,command};
    return entry.version===2?{...base,admissionRoute:admissionRoute?.wire}:base;
  }
}
export const JOURNAL_DATABASE = new InjectionToken<string>('Journal database', { providedIn:'root',factory:()=> 'blackstore-command-journal-v1' });
@Injectable({ providedIn: 'root' })
export class IndexedDbCommandJournal extends CommandJournal {
  private readonly name = inject(JOURNAL_DATABASE);
  private database(): Promise<IDBDatabase> {
    return new Promise((resolve,reject) => {
      const request = indexedDB.open(this.name, 1);
      request.onupgradeneeded = () => request.result.createObjectStore('intents', { keyPath: 'command.commandId' });
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error('Journal bloqueado'));
    });
  }
  private async transaction<T>(write: boolean, action: (store: IDBObjectStore, done: (value:T)=>void, fail:(error:unknown)=>void)=>void): Promise<T> {
    const db = await this.database();
    return new Promise((resolve,reject) => {
      const tx = db.transaction('intents', write ? 'readwrite' : 'readonly'); let value:T;
      tx.oncomplete = () => { db.close(); resolve(value); };
      tx.onabort = tx.onerror = () => { db.close(); reject(tx.error ?? new Error('Journal no confirmado')); };
      action(tx.objectStore('intents'), result => { value = result; }, () => tx.abort());
    });
  }
  list(): Promise<JournalSnapshot> {
    return this.transaction<JournalSnapshot>(false, (store,done) => {
      const request = store.getAll(); request.onsuccess = () => {
        const entries = request.result.map(raw => JournalDecoder.decode(raw));
        done({ entries: entries.filter((entry): entry is JournalEntry => entry !== null), quarantined: entries.some(entry => entry === null) });
      };
    }).then(async snapshot=>{
      const verified=await Promise.all(snapshot.entries.map(async entry=>entry.command instanceof SaleCommand &&
        await SaleFingerprintRegistry.codec(entry.command).hash(entry.command,entry.actorId)!==entry.expectedPayloadHash ? null : entry));
      return {entries:verified.filter((entry):entry is JournalEntry=>entry!==null),quarantined:snapshot.quarantined||verified.some(entry=>entry===null)};
    });
  }
  async prepare(entry: JournalEntry): Promise<void> {
    if(entry.command instanceof SaleCommand && (entry.fingerprintVersion!==entry.command.route.fingerprintVersion||(entry.admissionRoute??entry.command.route)!==entry.command.route||
      await SaleFingerprintRegistry.codec(entry.command).hash(entry.command,entry.actorId)!==entry.expectedPayloadHash))throw new Error('Fingerprint incompatible');
    return this.transaction(true, (store,done,fail) => {
      const request = store.getAll(); request.onsuccess = () => {
        // The read and insert share one readwrite transaction: two tabs cannot both claim.
        const occupied = request.result.some(raw => { const saved = JournalDecoder.decode(raw); return !saved || saved.phase !== JournalPhase.Resolved; });
        if (occupied || !JournalDecoder.decode(JournalDecoder.encode(entry))) { fail(new Error('Intención pendiente')); return; }
        store.add(JournalDecoder.encode(entry)); done(undefined);
      };
    });
  }
  transition(entry: JournalEntry, phase: JournalPhase): Promise<void> {
    return this.transaction(true, (store,done,fail) => {
      const request = store.get(entry.command.commandId); request.onsuccess = () => {
        const saved = JournalDecoder.decode(request.result,true);
        if (!saved || saved.family !== entry.family || saved.actorId !== entry.actorId || !sameScope(saved.scope,entry.scope) || saved.cashSessionId!==entry.cashSessionId ||
          saved.expectedPayloadHash!==entry.expectedPayloadHash || saved.fingerprintVersion!==entry.fingerprintVersion || saved.admissionRoute!==(entry.admissionRoute??(entry.command instanceof SaleCommand?entry.command.route:undefined)) || JSON.stringify(saved.command.body) !== JSON.stringify(entry.command.body)) { fail(new Error('Evidencia cambió')); return; }
        const next=saved.phase.nextPhase(phase);
        if(!next){fail(new Error('Transición de evidencia inválida'));return;}
        // Read, policy and write share the transaction. Terminal evidence cannot be reopened by a stale tab.
        if(next!==saved.phase)store.put(JournalDecoder.encode({ ...saved, phase:next }));
        done(undefined);
      };
    });
  }
}
