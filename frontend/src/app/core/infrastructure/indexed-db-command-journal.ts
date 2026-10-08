import { Injectable, InjectionToken, inject } from '@angular/core';
import { AccountingCommand, AccountingCommandKind, ExpenseOperation } from '../domain/accounting-command';
import { CommandJournal, JournalEntry, JournalFamily, JournalPhase, JournalSnapshot } from '../domain/command-journal';
import { SaleCommand, SaleCommandKind } from '../domain/sale-command';
import { PosWireMapper } from './pos-wire-mapper';
import { TicketMoney } from '../domain/ticket-transition';
import { PaymentMethod } from '../domain/pos-types';

/** Storage is untrusted evidence. This decoder is the only storage-to-domain boundary. */
export class JournalDecoder {
  static decode(raw: unknown): JournalEntry | null {
    try {
      if (JSON.stringify(raw).length > 32768 || !raw || typeof raw !== 'object') return null;
      const r = raw as Record<string, unknown>; const scope = r['scope'] as Record<string, unknown>;
      const command = r['command'] as Record<string, unknown>; const body = command?.['body'] as Record<string, unknown>;
      const family = JournalFamily.fromWire(r['family']); const phase = JournalPhase.fromWire(r['phase']);
      const id = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0;
      if (r['version'] !== 1 || !scope || typeof scope['origin'] !== 'string' || !/^https?:\/\//.test(scope['origin']) ||
        new URL(scope['origin']).origin !== scope['origin'] || !PosWireMapper.uuid(scope['clientInstanceId']) || typeof scope['deviceId'] !== 'string' || !scope['deviceId'].trim() || scope['deviceId'] !== scope['deviceId'].trim() || scope['deviceId'].length > 80 || !id(scope['terminalId']) ||
        !id(r['actorId']) || (r['cashSessionId'] !== null && !id(r['cashSessionId'])) || !PosWireMapper.uuid(r['tabId']) ||
        family === JournalFamily.Unknown || phase === JournalPhase.Unknown || phase === JournalPhase.Quarantined ||
        !command || !PosWireMapper.uuid(command['commandId']) || !body || Array.isArray(body) || body['commandId'] !== command['commandId']) return null;
      // Only scalar canonical request fields; no credentials, persisted permissions or nested objects.
      const allowed = ['commandId','clientInstanceId','deviceId','saleId','operationId','cashSessionId','terminalId','cashierId','openingCash','declaredCash','amount','paymentMethod','reason','originalPaymentId','evidenceRef','operation','expenseId','category','variantId','quantity','expectedPriceVersion','sku','productName','originalUnitPrice','discountAmount'];
      if (Object.entries(body).some(([key,value]) => !allowed.includes(key) || (value !== null && !['string','number','boolean'].includes(typeof value)))) return null;
      const identityValid = () => PosWireMapper.uuid(body['clientInstanceId']) && PosWireMapper.uuid(body['saleId']) && PosWireMapper.uuid(body['operationId']) &&
        body['clientInstanceId'] === scope['clientInstanceId'] && body['deviceId'] === scope['deviceId'];
      const money = (key:string,positive=false) => { const amount=TicketMoney.fromDecimal(body[key]);return !!amount && (positive ? amount.cents>0n : amount.cents>=0n); };
      const text = (key:string) => typeof body[key] === 'string' && !!(body[key] as string).trim();
      const frozen = Object.freeze({ ...body }); let decoded: AccountingCommand | SaleCommand;
      if (family === JournalFamily.Accounting) {
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
        const kind = SaleCommandKind.fromWire(command['kind']);
        const identity = { clientInstanceId: body['clientInstanceId'], deviceId: body['deviceId'], saleId: body['saleId'], operationId: body['operationId'] };
        if (kind === SaleCommandKind.Unknown || !id(r['cashSessionId']) || !PosWireMapper.uuid(identity.clientInstanceId) || !PosWireMapper.uuid(identity.saleId) || !PosWireMapper.uuid(identity.operationId) ||
          typeof identity.deviceId !== 'string' || !identity.deviceId.trim() || identity.deviceId.length > 80 || !identityValid() || command['cashSessionId'] !== r['cashSessionId']) return null;
        if (kind === SaleCommandKind.Reserve && (body['cashSessionId'] !== r['cashSessionId'] || !text('variantId') || !text('expectedPriceVersion') || !text('sku') || !text('productName') ||
          !id(body['quantity']) || !money('originalUnitPrice',true) || !money('discountAmount'))) return null;
        decoded = SaleCommand.fromJournal(kind, command['commandId'], identity as import('../domain/ticket-transition').TicketIdentity, r['cashSessionId'], frozen);
      }
      return Object.freeze({ version: 1, scope: Object.freeze({ ...scope }) as unknown as JournalEntry['scope'], command: decoded,
        family, phase, actorId: r['actorId'], cashSessionId: r['cashSessionId'] as number | null, tabId: r['tabId'] as string });
    } catch { return null; }
  }
  static encode(entry: JournalEntry): unknown {
    return { ...entry, family: entry.family.wire, phase: entry.phase.wire,
      command: { ...entry.command, kind: entry.command.kind.wire } };
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
    return this.transaction(false, (store,done) => {
      const request = store.getAll(); request.onsuccess = () => {
        const entries = request.result.map(raw => JournalDecoder.decode(raw));
        done({ entries: entries.filter((entry): entry is JournalEntry => entry !== null), quarantined: entries.some(entry => entry === null) });
      };
    });
  }
  prepare(entry: JournalEntry): Promise<void> {
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
        const saved = JournalDecoder.decode(request.result);
        if (!saved || saved.family !== entry.family || saved.actorId !== entry.actorId || JSON.stringify(saved.command.body) !== JSON.stringify(entry.command.body)) { fail(new Error('Evidencia cambió')); return; }
        store.put(JournalDecoder.encode({ ...saved, phase })); done(undefined);
      };
    });
  }
}
