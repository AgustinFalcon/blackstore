import { StaffPermission } from './session-types';
import { TicketIdentity, TicketMoney, sameTicketIdentity } from './ticket-transition';
import { SaleTextV2 } from './sale-text-v2';
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
  readonly fingerprintVersion:SaleFingerprintVersion;
}
export class SaleAdmissionVerification {
  static readonly Verified=new SaleAdmissionVerification();
  static readonly PayloadMismatch=new SaleAdmissionVerification();
  static readonly Unknown=new SaleAdmissionVerification();
  private constructor(){}
}
export class SaleCommandFailure {
  static readonly Validation=new SaleCommandFailure('VALIDATION',400); static readonly Forbidden=new SaleCommandFailure('FORBIDDEN',403);
  static readonly NotVisible=new SaleCommandFailure('NOT_VISIBLE',404); static readonly PayloadMismatch=new SaleCommandFailure('PAYLOAD_MISMATCH',409);
  static readonly ExistingOperationCommand=new SaleCommandFailure('EXISTING_OPERATION_COMMAND',409); static readonly Closed=new SaleCommandFailure('CLOSED',409);
  static readonly TransitionConflict=new SaleCommandFailure('TRANSITION_CONFLICT',409); static readonly NotActivated=new SaleCommandFailure('NOT_ACTIVATED',409);
  static readonly Paused=new SaleCommandFailure('PAUSED',409); static readonly LegacyContractDisabled=new SaleCommandFailure('LEGACY_CONTRACT_DISABLED',409);
  static readonly Unavailable=new SaleCommandFailure('UNAVAILABLE',503); static readonly Unknown=new SaleCommandFailure('UNKNOWN',0);
  private constructor(readonly wire:string,readonly httpStatus:number){}
  static fromWire(raw:unknown):SaleCommandFailure{return [this.Validation,this.Forbidden,this.NotVisible,this.PayloadMismatch,this.ExistingOperationCommand,
    this.Closed,this.TransitionConflict,this.NotActivated,this.Paused,this.LegacyContractDisabled,this.Unavailable].find(value=>value.wire===raw)??this.Unknown;}
}
export class SaleLineFailureCode {
  static readonly VariantMissing=new SaleLineFailureCode('VariantMissing'); static readonly CatalogTextInvalid=new SaleLineFailureCode('CatalogTextInvalid');
  static readonly SkuMismatch=new SaleLineFailureCode('SkuMismatch'); static readonly ProductNameMismatch=new SaleLineFailureCode('ProductNameMismatch');
  static readonly PriceVersionMismatch=new SaleLineFailureCode('PriceVersionMismatch'); static readonly PriceMismatch=new SaleLineFailureCode('PriceMismatch');
  static readonly DiscountNotAllowed=new SaleLineFailureCode('DiscountNotAllowed'); static readonly Unknown=new SaleLineFailureCode('Unknown');
  private constructor(readonly wire:string){}
  static fromWire(raw:unknown):SaleLineFailureCode{return [this.VariantMissing,this.CatalogTextInvalid,this.SkuMismatch,this.ProductNameMismatch,
    this.PriceVersionMismatch,this.PriceMismatch,this.DiscountNotAllowed].find(value=>value.wire===raw)??this.Unknown;}
}
export interface SaleLineFailure {readonly variantId:string;readonly code:SaleLineFailureCode;}
export interface SaleAdmission { readonly outcome: SaleAdmissionOutcome; readonly receipt: SaleAdmissionReceipt | null; readonly verification?:SaleAdmissionVerification;
  readonly failure?:SaleCommandFailure;readonly lineFailures?:readonly SaleLineFailure[]; }
export class SaleFingerprintVersion {
  static readonly AdmissionV1=new SaleFingerprintVersion('sale-admission-v1');
  static readonly AdmissionV2=new SaleFingerprintVersion('sale-admission-v2');
  static readonly Unknown=new SaleFingerprintVersion('Unknown');
  private constructor(readonly wire:string){}
  static fromWire(raw:unknown):SaleFingerprintVersion{return [this.AdmissionV1,this.AdmissionV2].find(value=>value.wire===raw)??this.Unknown;}
}
export class SaleAdmissionFamily {
  static readonly V2=new SaleAdmissionFamily('sale-v2');
  static readonly V3=new SaleAdmissionFamily('sale-v3');
  static readonly Unknown=new SaleAdmissionFamily('unknown');
  private constructor(readonly wire:string){}
  static fromWire(raw:unknown):SaleAdmissionFamily{return [this.V2,this.V3].find(value=>value.wire===raw)??this.Unknown;}
}
export class SaleAdmissionRoute {
  static readonly ReserveV2=new SaleAdmissionRoute('/api/v2/sales/reservations',SaleAdmissionFamily.V2,SaleFingerprintVersion.AdmissionV1,SaleCommandKind.Reserve);
  static readonly ReserveV3=new SaleAdmissionRoute('/api/v3/sales/reservations',SaleAdmissionFamily.V3,SaleFingerprintVersion.AdmissionV2,SaleCommandKind.Reserve);
  static readonly CommitV2=new SaleAdmissionRoute('/api/v2/sales/:operationId/commit',SaleAdmissionFamily.V2,SaleFingerprintVersion.AdmissionV1,SaleCommandKind.Commit);
  static readonly ReleaseV2=new SaleAdmissionRoute('/api/v2/sales/:operationId/release',SaleAdmissionFamily.V2,SaleFingerprintVersion.AdmissionV1,SaleCommandKind.Release);
  static readonly Unknown=new SaleAdmissionRoute('',SaleAdmissionFamily.Unknown,SaleFingerprintVersion.Unknown,SaleCommandKind.Unknown);
  private constructor(readonly wire:string,readonly family:SaleAdmissionFamily,readonly fingerprintVersion:SaleFingerprintVersion,readonly kind:SaleCommandKind){}
  static fromWire(raw:unknown):SaleAdmissionRoute{return [this.ReserveV2,this.ReserveV3,this.CommitV2,this.ReleaseV2].find(value=>value.wire===raw)??this.Unknown;}
  path(operationId:string):string{return this.kind===SaleCommandKind.Reserve?this.wire:this.wire.replace(':operationId',encodeURIComponent(operationId));}
}
export interface SaleReservationLineV2 {
  readonly variantId:string; readonly quantity:number; readonly expectedPriceVersion:string;
  readonly sku:string; readonly productName:string; readonly originalUnitPrice:string; readonly discountAmount:string;
}
export class SaleCommand {
  private constructor(readonly kind: SaleCommandKind, readonly commandId: string, readonly identity: TicketIdentity,
    readonly cashSessionId: number, readonly body: Readonly<Record<string, unknown>>,readonly route:SaleAdmissionRoute) {}
  static create(kind: SaleCommandKind, identity: TicketIdentity, cashSessionId: number, fields: Readonly<Record<string, unknown>>): SaleCommand {
    if (kind === SaleCommandKind.Unknown || !Number.isSafeInteger(cashSessionId) || cashSessionId < 1) throw new Error('Comando inválido');
    const commandId = crypto.randomUUID();
    const reason = typeof fields['reason'] === 'string' ? fields['reason'].trim() || null : fields['reason'] ?? null;
    const route=kind===SaleCommandKind.Reserve?SaleAdmissionRoute.ReserveV2:kind===SaleCommandKind.Commit?SaleAdmissionRoute.CommitV2:SaleAdmissionRoute.ReleaseV2;
    return new SaleCommand(kind, commandId, Object.freeze({ ...identity }), cashSessionId,
      Object.freeze({ ...fields, ...identity, commandId, reason }),route);
  }
  static createMultilineReserve(identity:TicketIdentity,cashSessionId:number,lines:readonly SaleReservationLineV2[],reason?:string|null):SaleCommand {
    if(!Number.isSafeInteger(cashSessionId)||cashSessionId<1||lines.length<1||lines.length>100)throw new Error('Comando inválido');
    const normalizedIdentity=Object.freeze({clientInstanceId:this.uuid(identity.clientInstanceId),deviceId:SaleTextV2.ref(identity.deviceId),
      saleId:SaleTextV2.ref(identity.saleId),operationId:this.uuid(identity.operationId)});
    const seen=new Set<string>();
    const normalizedLines=Object.freeze(lines.map(line=>{
      const variantId=SaleTextV2.ref(line.variantId); if(seen.has(variantId))throw new Error('Variante repetida');seen.add(variantId);
      if(!Number.isSafeInteger(line.quantity)||line.quantity<1||line.quantity>2147483647)throw new Error('Cantidad inválida');
      const price=TicketMoney.fromDecimal(line.originalUnitPrice),discount=TicketMoney.fromDecimal(line.discountAmount);
      if(!price||price.cents<=0n||!discount||discount.cents!==0n)throw new Error('Dinero inválido');
      return Object.freeze({variantId,quantity:line.quantity,expectedPriceVersion:SaleTextV2.ref(line.expectedPriceVersion),sku:SaleTextV2.ref(line.sku),
        productName:SaleTextV2.name(line.productName),originalUnitPrice:price.decimal,discountAmount:discount.decimal});
    }));
    const normalizedReason=SaleTextV2.reason(reason);
    const placeholder='00000000-0000-0000-0000-000000000000';
    const candidate=Object.freeze({...normalizedIdentity,commandId:placeholder,cashSessionId,lines:normalizedLines,reason:normalizedReason});
    if(new TextEncoder().encode(JSON.stringify(candidate)).length>524288)throw new Error('Payload excede presupuesto durable');
    const commandId=crypto.randomUUID();
    const body=Object.freeze({...candidate,commandId});
    return new SaleCommand(SaleCommandKind.Reserve,commandId,normalizedIdentity,cashSessionId,body,SaleAdmissionRoute.ReserveV3);
  }
  static fromJournal(kind: SaleCommandKind, commandId: string, identity: TicketIdentity, cashSessionId: number, body: Readonly<Record<string, unknown>>,route:SaleAdmissionRoute=SaleAdmissionRoute.Unknown): SaleCommand {
    const resolved=route===SaleAdmissionRoute.Unknown?(kind===SaleCommandKind.Reserve?SaleAdmissionRoute.ReserveV2:kind===SaleCommandKind.Commit?SaleAdmissionRoute.CommitV2:SaleAdmissionRoute.ReleaseV2):route;
    return new SaleCommand(kind, commandId, Object.freeze({ ...identity }), cashSessionId, Object.freeze({ ...body }),resolved);
  }
  accepts(receipt: SaleAdmissionReceipt, actorId: number,expectedPayloadHash:string|null): boolean {
    return receipt.commandId === this.commandId && receipt.kind === this.kind && receipt.actorId === actorId &&
      receipt.cashSessionId === this.cashSessionId && sameTicketIdentity(this.identity, receipt) && !!expectedPayloadHash && receipt.payloadHash===expectedPayloadHash;
  }
  private static uuid(raw:string):string {if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(SaleTextV2.validate(raw)))throw new Error('UUID inválido');return raw;}
}
