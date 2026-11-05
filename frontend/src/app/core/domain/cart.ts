import { TicketMoney } from './ticket-transition';
import { SaleReservationLineV2 } from './sale-command';
import { SaleTextV2 } from './sale-text-v2';

export class CartPhase {
  static readonly Editing=new CartPhase('Editing','Editando carrito',true);
  static readonly AwaitingReservation=new CartPhase('AwaitingReservation','Esperando reserva');
  static readonly Reserved=new CartPhase('Reserved','Reserva comprobada');
  static readonly AwaitingPayment=new CartPhase('AwaitingPayment','Esperando cobro');
  static readonly AwaitingCommit=new CartPhase('AwaitingCommit','Esperando confirmación');
  static readonly Completed=new CartPhase('Completed','Venta completada');
  static readonly RecoveryRequired=new CartPhase('RecoveryRequired','Recuperación requerida');
  static readonly Unknown=new CartPhase('Unknown','Estado no comprobado');
  private constructor(readonly wire:string,readonly label:string,readonly editable=false){}
  static fromWire(raw:unknown):CartPhase{return [this.Editing,this.AwaitingReservation,this.Reserved,this.AwaitingPayment,this.AwaitingCommit,this.Completed,this.RecoveryRequired]
    .find(value=>value.wire===raw)??this.Unknown;}
}

export class CartLine {
  private constructor(readonly variantId:string,readonly sku:string,readonly productName:string,readonly quantity:number,
    readonly expectedPriceVersion:string,readonly originalUnitPrice:TicketMoney,readonly discountAmount:TicketMoney){}
  static create(input:SaleReservationLineV2):CartLine {
    const price=TicketMoney.fromDecimal(input.originalUnitPrice),discount=TicketMoney.fromDecimal(input.discountAmount);
    if(!Number.isSafeInteger(input.quantity)||input.quantity<1||input.quantity>2147483647||
      !price||price.cents<=0n||!discount||discount.cents!==0n)throw new Error('Línea de carrito inválida');
    return new CartLine(SaleTextV2.ref(input.variantId),SaleTextV2.ref(input.sku),SaleTextV2.name(input.productName),input.quantity,
      SaleTextV2.ref(input.expectedPriceVersion),price,discount);
  }
  withQuantity(quantity:number):CartLine {
    if(!Number.isSafeInteger(quantity)||quantity<1||quantity>2147483647)throw new Error('Cantidad inválida');
    return new CartLine(this.variantId,this.sku,this.productName,quantity,this.expectedPriceVersion,this.originalUnitPrice,this.discountAmount);
  }
  get subtotal():TicketMoney {const value=moneyFromCents(this.originalUnitPrice.cents*BigInt(this.quantity));if(!value)throw new Error('Total fuera de rango');return value;}
  toReservationLine():SaleReservationLineV2{return Object.freeze({variantId:this.variantId,sku:this.sku,productName:this.productName,quantity:this.quantity,
    expectedPriceVersion:this.expectedPriceVersion,originalUnitPrice:this.originalUnitPrice.decimal,discountAmount:this.discountAmount.decimal});}
}

export class CartDraft {
  private constructor(readonly lines:readonly CartLine[]){}
  static empty():CartDraft{return new CartDraft(Object.freeze([]));}
  add(line:CartLine):CartDraft {
    const current=this.lines.find(item=>item.variantId===line.variantId);
    if(current){const quantity=current.quantity+line.quantity;if(!Number.isSafeInteger(quantity)||quantity>2147483647)throw new Error('Cantidad fuera de rango');
      return this.replace(current.withQuantity(quantity));}
    if(this.lines.length>=100)throw new Error('Máximo de variantes alcanzado');
    return new CartDraft(Object.freeze([...this.lines,line]));
  }
  change(variantId:string,quantity:number):CartDraft {const current=this.lines.find(item=>item.variantId===variantId);if(!current)throw new Error('Variante ausente');return this.replace(current.withQuantity(quantity));}
  remove(variantId:string):CartDraft{return new CartDraft(Object.freeze(this.lines.filter(item=>item.variantId!==variantId)));}
  get total():TicketMoney|null {
    if(!this.lines.length)return null;
    const cents=this.lines.reduce((sum,line)=>sum+line.subtotal.cents,0n);
    return moneyFromCents(cents);
  }
  freeze():readonly SaleReservationLineV2[] {if(!this.lines.length||!this.total)throw new Error('Carrito inválido');return Object.freeze(this.lines.map(line=>Object.freeze(line.toReservationLine())));}
  private replace(line:CartLine):CartDraft{return new CartDraft(Object.freeze(this.lines.map(item=>item.variantId===line.variantId?line:item)));}
}

export interface ReservedCartSnapshot {readonly lines:readonly SaleReservationLineV2[];readonly total:TicketMoney;}

function moneyFromCents(cents:bigint):TicketMoney|null {
  const absolute=cents<0n?-cents:cents;
  return TicketMoney.fromDecimal(`${cents<0n?'-':''}${absolute/100n}.${String(absolute%100n).padStart(2,'0')}`);
}
