import { DemoPaymentMethod, DemoRole, DemoSnapshot, SaleLine } from './demo-types';
export interface CheckoutContext { readonly snapshot:DemoSnapshot; readonly method:DemoPaymentMethod; readonly received:number; readonly total:number; }
interface CheckoutStep { validate(context:CheckoutContext):void; }
class CartStep implements CheckoutStep { validate({snapshot}:CheckoutContext):void{if(!snapshot.cart.length)throw new Error('Agregá productos antes de cobrar.');for(const line of snapshot.cart){const p=snapshot.products.find(p=>p.id===line.productId);if(!p||!p.active||p.version!==line.version||p.price!==line.price||line.quantity>p.stock)throw new Error('El catálogo cambió. Revisá las cantidades y precios del carrito antes de cobrar.');}} }
class CashStep implements CheckoutStep { validate({snapshot}:CheckoutContext):void{if(!snapshot.cashStatus.canSell)throw new Error('Abrí la caja antes de cobrar.');if(snapshot.role===DemoRole.Unknown)throw new Error('Elegí un perfil válido.');} }
class PaymentStep implements CheckoutStep { validate({method,received,total}:CheckoutContext):void{if(method===DemoPaymentMethod.Unknown)throw new Error('Elegí un medio de pago válido.');if(!Number.isSafeInteger(received)||received<0)throw new Error('Ingresá un importe válido, sin valores negativos.');if(method.isCash&&received<total)throw new Error('El efectivo recibido no cubre el total.');} }
export class CheckoutFlow {
 private readonly steps:readonly CheckoutStep[]=[new CashStep(),new CartStep(),new PaymentStep()];
 validate(context:CheckoutContext):readonly SaleLine[]{this.steps.forEach(step=>step.validate(context));return context.snapshot.cart.map(line=>{const p=context.snapshot.products.find(p=>p.id===line.productId)!;return {productId:p.id,name:p.name,sku:p.sku,category:p.category,quantity:line.quantity,price:line.price,cost:p.cost};});}
}
