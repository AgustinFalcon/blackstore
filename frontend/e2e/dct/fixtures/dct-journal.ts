import { Page } from '@playwright/test';
import { JournalPhase, JournalFamily } from '../../../src/app/core/domain/command-journal';

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
    const row = raw as { phase: unknown; family: unknown; actorId: number;
      scope: { terminalId: number; clientInstanceId: string }; command: { commandId: string } };
    return { ...row, phase: JournalPhase.fromWire(row.phase), family: JournalFamily.fromWire(row.family) };
  });
}
