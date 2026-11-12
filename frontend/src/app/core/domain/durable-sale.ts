import { StaffPermission } from './session-types';
import { PaymentMethod, PaymentStatus } from './pos-types';
import { PaymentCoverage, TicketIdentity, TicketMoney } from './ticket-transition';
import { SaleTextV2 } from './sale-text-v2';
import { RecordedInstant } from './recorded-instant';

export class AllowedAction {
  static readonly CapturePayment = new AllowedAction('CAPTURE_PAYMENT', 'Completar pago', StaffPermission.PaymentCapture);
  static readonly Commit = new AllowedAction('COMMIT', 'Confirmar venta', StaffPermission.SaleCommit);
  static readonly Release = new AllowedAction('RELEASE', 'Liberar reserva', StaffPermission.SaleRelease);
  static readonly ReversePayment = new AllowedAction('REVERSE_PAYMENT', 'Reversar pago', StaffPermission.PaymentReverse);
  static readonly Unknown = new AllowedAction('UNKNOWN', 'Acción no disponible', StaffPermission.Unknown);
  static readonly values = [AllowedAction.CapturePayment, AllowedAction.Commit, AllowedAction.Release, AllowedAction.ReversePayment] as const;
  private constructor(readonly wire: string, readonly label: string, readonly permission: StaffPermission) {}
  static fromWire(raw: unknown): AllowedAction { return AllowedAction.values.find(value => value.wire === raw) ?? AllowedAction.Unknown; }
}

export class DurableSaleState {
  static readonly PendingReservation = new DurableSaleState('PENDING_RESERVATION', 'Reserva pendiente');
  static readonly Reserved = new DurableSaleState('RESERVED', 'Reservada', true);
  static readonly PaymentCaptured = new DurableSaleState('PAYMENT_CAPTURED', 'Pago registrado', true);
  static readonly CommitPending = new DurableSaleState('COMMIT_PENDING', 'Confirmación pendiente');
  static readonly Committed = new DurableSaleState('COMMITTED', 'Venta confirmada');
  static readonly ReleasePending = new DurableSaleState('RELEASE_PENDING', 'Liberación pendiente');
  static readonly Released = new DurableSaleState('RELEASED', 'Reserva liberada');
  static readonly ReconciliationRequired = new DurableSaleState('RECONCILIATION_REQUIRED', 'Requiere reconciliación');
  static readonly LegacyIncomplete = new DurableSaleState('LEGACY_INCOMPLETE', 'Histórico incompleto · sólo consulta');
  static readonly Unknown = new DurableSaleState('UNKNOWN', 'Estado desconocido · sólo consulta');
  static readonly values = [DurableSaleState.PendingReservation, DurableSaleState.Reserved, DurableSaleState.PaymentCaptured,
    DurableSaleState.CommitPending, DurableSaleState.Committed, DurableSaleState.ReleasePending, DurableSaleState.Released,
    DurableSaleState.ReconciliationRequired, DurableSaleState.LegacyIncomplete] as const;
  private constructor(readonly wire: string, readonly label: string, readonly acceptsCommands = false) {}
  static fromWire(raw: unknown): DurableSaleState { return DurableSaleState.values.find(value => value.wire === raw) ?? DurableSaleState.Unknown; }
  permits(action: AllowedAction, sale: DurableSaleDetail): boolean {
    if (!this.acceptsCommands || !sale.valid || !sale.allowedActions.includes(action)) return false;
    if (action === AllowedAction.CapturePayment) return (sale.coverage === PaymentCoverage.Partial || sale.coverage === PaymentCoverage.Unpaid) && !!sale.pending && sale.pending.cents > 0n;
    if (action === AllowedAction.Commit) return sale.coverage === PaymentCoverage.Paid && sale.pending?.cents === 0n;
    if (action === AllowedAction.Release) return sale.coverage === PaymentCoverage.Unpaid && !!sale.total && sale.pending?.cents === sale.total.cents;
    if (action === AllowedAction.ReversePayment) return sale.hasPaymentHistory === true && sale.payments.some(payment => payment.reversibility.permitsReverse);
    return false;
  }
}

export interface DurableSaleSummary {
  readonly identity: TicketIdentity;
  readonly cashSessionId: number;
  readonly cashierId: number;
  readonly createdBy: number | null;
  readonly status: DurableSaleState;
  readonly total: TicketMoney | null;
  readonly createdAt: RecordedInstant;
  readonly updatedAt: RecordedInstant;
}
export interface DurableSaleLine {
  readonly metadata:DurableLineMetadata;
  readonly variantId:string|null;
  readonly sku: string;
  readonly productName: string;
  readonly quantity: number;
  readonly originalUnitPrice:TicketMoney|null;
  readonly priceVersion:string|null;
  readonly total: TicketMoney | null;
}
export class DurableLineMetadata {
  static readonly Complete=new DurableLineMetadata('COMPLETE','Metadatos completos');
  static readonly LegacyV1=new DurableLineMetadata('LEGACY_V1','Histórico sin metadatos de variante');
  static readonly Unknown=new DurableLineMetadata('UNKNOWN','Metadatos inconsistentes');
  private constructor(readonly wire:string,readonly label:string){}
  static fromWire(rawVariantId:unknown,rawOriginalUnitPrice:unknown,rawPriceVersion:unknown):DurableLineMetadataValue {
    const ref=(raw:unknown):string|null=>{try{return typeof raw==='string'?SaleTextV2.ref(raw):null;}catch{return null;}};
    const variantId=ref(rawVariantId);
    const originalUnitPrice=TicketMoney.fromDecimal(rawOriginalUnitPrice);
    const priceVersion=ref(rawPriceVersion);
    const metadata=variantId&&originalUnitPrice&&priceVersion?this.Complete:
      rawVariantId===null&&originalUnitPrice!==null&&rawPriceVersion===null?this.LegacyV1:this.Unknown;
    return Object.freeze({metadata,variantId,originalUnitPrice,priceVersion});
  }
}
export interface DurableLineMetadataValue {
  readonly metadata:DurableLineMetadata;
  readonly variantId:string|null;
  readonly originalUnitPrice:TicketMoney|null;
  readonly priceVersion:string|null;
}
export interface DurableSalePayment {
  readonly originalPaymentId: number | null;
  readonly reversibility: PaymentReversibility;
  readonly paymentId: number;
  readonly status: PaymentStatus;
  readonly method: PaymentMethod;
  readonly amount: TicketMoney | null;
  readonly fee: TicketMoney | null;
  readonly createdAt: RecordedInstant;
}
/** Linkage is validated over the entire sale snapshot before any capture can be reversed. */
export class PaymentReversibility {
  static readonly Reversible = new PaymentReversibility('Puede reversarse',true);
  static readonly NotCapture = new PaymentReversibility('No es una captura',false);
  static readonly AlreadyRefunded = new PaymentReversibility('Captura ya reversada',false);
  static readonly Unknown = new PaymentReversibility('Reversibilidad no comprobada',false);
  private constructor(readonly label:string,readonly permitsReverse:boolean) {}
  static forPayment(payment:Pick<DurableSalePayment,'paymentId'|'status'|'amount'|'method'>,payments:readonly Pick<DurableSalePayment,'paymentId'|'status'|'amount'|'method'|'originalPaymentId'>[]):PaymentReversibility {
    if(!this.validLedger(payments))return this.Unknown;
    if(payment.status!==PaymentStatus.Captured) return this.NotCapture;
    return payments.some(p=>p.originalPaymentId===payment.paymentId) ? this.AlreadyRefunded : this.Reversible;
  }
  static validLedger(payments:readonly Pick<DurableSalePayment,'paymentId'|'status'|'amount'|'method'|'originalPaymentId'>[]):boolean {
    if(new Set(payments.map(p=>p.paymentId)).size!==payments.length)return false;
    const referenced=new Set<number>();
    return payments.every(p=>{
      if(!Number.isSafeInteger(p.paymentId) || p.paymentId<=0 || p.method===PaymentMethod.Unknown || !p.amount || p.amount.cents<=0n)return false;
      if(p.status===PaymentStatus.Captured)return p.originalPaymentId===null;
      if(p.status!==PaymentStatus.Refunded || !p.originalPaymentId || referenced.has(p.originalPaymentId))return false;
      referenced.add(p.originalPaymentId);
      const original=payments.find(c=>c.paymentId===p.originalPaymentId);
      return !!original && original!==p && original.status===PaymentStatus.Captured && original.originalPaymentId===null &&
        p.method!==PaymentMethod.Unknown && original.method===p.method && !!p.amount && p.amount.cents>0n && original.amount?.cents===p.amount.cents;
    });
  }
}
export interface DurableSaleDetail extends DurableSaleSummary {
  readonly pending: TicketMoney | null;
  readonly coverage: PaymentCoverage;
  readonly hasPaymentHistory: boolean | null;
  readonly lines: readonly DurableSaleLine[];
  readonly payments: readonly DurableSalePayment[];
  readonly allowedActions: readonly AllowedAction[];
  readonly valid: boolean;
  readonly receipt: string | null;
  readonly reservationRef: string | null;
  readonly pendingCommand: DurableCommandKind | null;
}
export interface DurableSalePage { readonly items: readonly DurableSaleSummary[]; readonly nextCursor: string | null; }

export class DurableCommandKind {
  static readonly Reserve = new DurableCommandKind('RESERVE', 'Reserva pendiente');
  static readonly Commit = new DurableCommandKind('COMMIT', 'Confirmación pendiente');
  static readonly Release = new DurableCommandKind('RELEASE', 'Liberación pendiente');
  static readonly Unknown = new DurableCommandKind('UNKNOWN', 'Comando desconocido');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): DurableCommandKind {
    return [DurableCommandKind.Reserve, DurableCommandKind.Commit, DurableCommandKind.Release].find(value => value.wire === raw) ?? DurableCommandKind.Unknown;
  }
}
