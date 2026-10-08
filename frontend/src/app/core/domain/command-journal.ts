import { AccountingCommand } from './accounting-command';
import { SaleCommand } from './sale-command';
export class JournalFamily {
  static readonly Accounting = new JournalFamily('Accounting');
  static readonly Sale = new JournalFamily('Sale');
  static readonly Unknown = new JournalFamily('Unknown');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): JournalFamily { return [this.Accounting, this.Sale].find(value => value.wire === raw) ?? this.Unknown; }
}
export class JournalPhase {
  static readonly Prepared = new JournalPhase('Prepared');
  static readonly AwaitingReceipt = new JournalPhase('AwaitingReceipt');
  static readonly ReceiptVerifiedAwaitingRefresh = new JournalPhase('ReceiptVerifiedAwaitingRefresh');
  static readonly Resolved = new JournalPhase('Resolved');
  static readonly Quarantined = new JournalPhase('Quarantined');
  static readonly Unknown = new JournalPhase('Unknown');
  private constructor(readonly wire: string) {}
  static fromWire(raw: unknown): JournalPhase { return [this.Prepared, this.AwaitingReceipt, this.ReceiptVerifiedAwaitingRefresh, this.Resolved, this.Quarantined].find(value => value.wire === raw) ?? this.Unknown; }
}
export interface JournalScope { readonly origin: string; readonly clientInstanceId: string; readonly deviceId: string; readonly terminalId: number; }
export interface JournalEntry {
  readonly version: 1; readonly scope: JournalScope; readonly family: JournalFamily; readonly command: AccountingCommand | SaleCommand;
  readonly actorId: number; readonly cashSessionId: number | null; readonly phase: JournalPhase; readonly tabId: string;
}
export interface JournalSnapshot { readonly entries: readonly JournalEntry[]; readonly quarantined: boolean; }
export abstract class CommandJournal {
  abstract list(): Promise<JournalSnapshot>;
  abstract prepare(entry: JournalEntry): Promise<void>;
  abstract transition(entry: JournalEntry, phase: JournalPhase): Promise<void>;
}
export function sameScope(a: JournalScope, b: JournalScope): boolean {
  return a.origin === b.origin && a.clientInstanceId === b.clientInstanceId && a.deviceId === b.deviceId && a.terminalId === b.terminalId;
}
