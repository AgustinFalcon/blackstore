import { Page } from '@playwright/test';
import { JournalPhase, JournalFamily } from '../../../src/app/core/domain/command-journal';
import { AccountingCommandKind } from '../../../src/app/core/domain/accounting-command';
import { SaleCommandKind } from '../../../src/app/core/domain/sale-command';

/** Read real browser storage; closed vocabulary translation at this storage boundary. */
export async function journal(page: Page) {
  const rows = await page.evaluate(() => new Promise<unknown[]>((resolve, reject) => {
    const request = indexedDB.open('blackstore-command-journal-v1', 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const db = request.result;
      const tx = db.transaction('intents', 'readonly');
      const read = tx.objectStore('intents').getAll();
      let rows: unknown[] = [];
      read.onsuccess = () => { rows = read.result; };
      tx.oncomplete = () => { db.close(); resolve(rows); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    };
  }));
  return rows.map(raw => {
    const row = raw as { phase: unknown; family: unknown; actorId: number; tabId: string;
      scope: { terminalId: number; clientInstanceId: string; deviceId: string; origin: string };
      command: { commandId: string; kind: unknown; body: Record<string, unknown> } };
    const family = JournalFamily.fromWire(row.family);
    return { ...row, phase: JournalPhase.fromWire(row.phase), family,
      command: { ...row.command, kind: family === JournalFamily.Sale ? SaleCommandKind.fromWire(row.command.kind) : AccountingCommandKind.fromWire(row.command.kind) } };
  });
}
