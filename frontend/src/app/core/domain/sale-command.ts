import { StaffPermission } from './session-types';
import { TicketIdentity, sameTicketIdentity } from './ticket-transition';
export class SaleCommandKind {
  static readonly Reserve = new SaleCommandKind('Reserve', StaffPermission.SaleReserve);
  static readonly Commit = new SaleCommandKind('Commit', StaffPermission.SaleCommit);
  static readonly Release = new SaleCommandKind('Release', StaffPermission.SaleRelease);
  static readonly Unknown = new SaleCommandKind('Unknown', StaffPermission.Unknown);
  private constructor(readonly wire: string, readonly permission: StaffPermission) {}
  static fromWire(raw: unknown): SaleCommandKind { return [this.Reserve, this.Commit, this.Release].find(value => value.wire === raw) ?? this.Unknown; }
}
export class SaleAdmissionOutcome {
  static readonly Accepted = new SaleAdmissionOutcome('Accepted', 'Intención admitida; falta comprobar venta');
  static readonly NotFound = new SaleAdmissionOutcome('NotFound', 'Sin recibo visible; resultado incierto');
  static readonly Unavailable = new SaleAdmissionOutcome('Unavailable', 'Recibo no disponible');
  static readonly Rejected = new SaleAdmissionOutcome('Rejected', 'Intención rechazada');
  static readonly Unknown = new SaleAdmissionOutcome('Unknown', 'Resultado desconocido');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): SaleAdmissionOutcome { return [this.Accepted, this.NotFound, this.Unavailable, this.Rejected].find(value => value.wire === raw) ?? this.Unknown; }
}
export interface SaleAdmissionReceipt extends TicketIdentity {
  readonly commandId: string; readonly kind: SaleCommandKind; readonly payloadHash: string;
  readonly actorId: number; readonly cashSessionId: number; readonly intentId: number; readonly outboxId: number; readonly acceptedAt: string;
}
export interface SaleAdmission { readonly outcome: SaleAdmissionOutcome; readonly receipt: SaleAdmissionReceipt | null; }
export class SaleCommand {
  private constructor(readonly kind: SaleCommandKind, readonly commandId: string, readonly identity: TicketIdentity,
    readonly cashSessionId: number, readonly body: Readonly<Record<string, unknown>>) {}
  static create(kind: SaleCommandKind, identity: TicketIdentity, cashSessionId: number, fields: Readonly<Record<string, unknown>>): SaleCommand {
    if (kind === SaleCommandKind.Unknown || !Number.isSafeInteger(cashSessionId) || cashSessionId < 1) throw new Error('Comando inválido');
    const commandId = crypto.randomUUID();
    const reason = typeof fields['reason'] === 'string' ? fields['reason'].trim() || null : fields['reason'] ?? null;
    return new SaleCommand(kind, commandId, Object.freeze({ ...identity }), cashSessionId,
      Object.freeze({ ...fields, ...identity, commandId, reason }));
  }
  static fromJournal(kind: SaleCommandKind, commandId: string, identity: TicketIdentity, cashSessionId: number, body: Readonly<Record<string, unknown>>): SaleCommand {
    return new SaleCommand(kind, commandId, Object.freeze({ ...identity }), cashSessionId, Object.freeze({ ...body }));
  }
  accepts(receipt: SaleAdmissionReceipt, actorId: number): boolean {
    return receipt.commandId === this.commandId && receipt.kind === this.kind && receipt.actorId === actorId &&
      receipt.cashSessionId === this.cashSessionId && sameTicketIdentity(this.identity, receipt);
  }
}
