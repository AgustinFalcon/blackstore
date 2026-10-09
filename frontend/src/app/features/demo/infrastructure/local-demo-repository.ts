import { DemoRepository } from '../application/demo-repository';
import { Customer, DemoCashStatus, DemoMovementKind, DemoProductArt, DemoPaymentAttempt, DemoPaymentStatus, DemoPaymentMethod, DemoRole, DemoSaleStatus, DemoScenario, DemoSnapshot, Product, Sale } from '../domain/demo-types';
import { CheckoutFlow } from '../domain/checkout-flow';

/** In-memory adapter. Never reads session, recovery storage or HTTP. */
export class LocalDemoRepository extends DemoRepository {
  private state!: DemoSnapshot;
  private readonly listeners = new Set<(snapshot: DemoSnapshot) => void>();
  private readonly receipts = new Map<string, Sale>();
  private readonly attempts = new Map<string, DemoPaymentAttempt>();
  private sequence = 100;
  private failed = false;
  constructor(private readonly clock: () => Date = () => new Date()) { super(); this.reset(DemoScenario.Normal); }
  snapshot(): DemoSnapshot { return this.state; }
  subscribe(listener: (snapshot: DemoSnapshot) => void): () => void { this.listeners.add(listener); listener(this.state); return () => this.listeners.delete(listener); }
  private freeze<T>(value:T):T { if(value&&typeof value==='object'){Object.values(value).forEach(child=>this.freeze(child));if(!Object.isFrozen(value))Object.freeze(value);}return value; }
  private publish(patch: Partial<DemoSnapshot>): void { this.state = this.freeze({...this.state, ...patch}); this.listeners.forEach(listener => listener(this.state)); }
  private id(): string { return String(++this.sequence); }
  private now(): string { return this.clock().toISOString(); }
  private cents(value: number): void { if (!Number.isSafeInteger(value) || value < 0) throw new Error('Ingresá un importe válido, sin valores negativos.'); }
  private manager(): void { if (!this.state.role.canManage) throw new Error('Esta acción requiere el perfil Encargada. Cambialo en Escenarios.'); }
  private editable(): void { if (this.state.payment?.status.pending) throw new Error('Confirmá o cancelá el pago pendiente antes de modificar la venta.'); }
  reset(scenario: DemoScenario): void {
    if (scenario === DemoScenario.Unknown) throw new Error('Elegí un escenario disponible.');
    this.sequence = 100; this.failed = false; this.receipts.clear(); this.attempts.clear();
    const names = ['Remera esencial', 'Camisa Oxford', 'Buzo urbano', 'Jean recto', 'Pantalón cargo', 'Short deportivo', 'Zapatillas blancas', 'Botas cuero', 'Sandalias verano', 'Mochila urbana', 'Cartera mini', 'Riñonera', 'Gorra clásica', 'Cinturón cuero', 'Medias pack', 'Remera rayada', 'Jean azul', 'Buzo oversize'];
    const categories = ['Indumentaria', 'Calzado', 'Accesorios', 'Básicos'];
    const categoryIndex=[0,0,0,0,0,0,1,1,1,2,2,2,2,2,3,3,0,0];
    const artKeys=['shirt','shirt','shirt','pants','pants','shorts','shoe','shoe','shoe','bag','bag','bag','cap','belt','socks','shirt','pants','shirt'];
    const products: Product[] = names.map((name, i) => ({id: `p${i+1}`, name, sku: `BS-${String(i+1).padStart(3,'0')}`, category: categories[categoryIndex[i]], art:DemoProductArt.fromWire(artKeys[i]), price: (12500+i*2300)*100, cost: (6200+i*1000)*100, stock: scenario.low ? i%4 : i === 4 ? 0 : i%5===0 ? 2 : 12+i, active: i !== 17, version: 1, icon: ['◈','✦','▧','◎'][categoryIndex[i]]}));
    const customers: Customer[] = ['Cliente ocasional','Ana Demo','Bruno Demo','Carla Demo','Diego Demo','Elena Demo','Franco Demo'].map((name,i)=>({id:`c${i}`,name,email:i ? `demo${i}@example.invalid`:'',phone:i ? `000-000${i}`:''}));
    const date = this.now();
    const sales: Sale[] = scenario.empty ? [] : Array.from({length:8},(_,i)=>{const product=products[i]; return {id:`V-${90+i}`,date:new Date(this.clock().getTime()-i*86400000).toISOString(),customerId:customers[1+i%6].id,customerName:customers[1+i%6].name,lines:[{productId:product.id,name:product.name,sku:product.sku,category:product.category,price:product.price,cost:product.cost,quantity:1}], total:product.price,discount:0,method:DemoPaymentMethod.values[i%3],cashAmount:i%3===0?product.price:0,received:product.price,reference:'Demostración',status:i===7?DemoSaleStatus.Reversed:DemoSaleStatus.Completed,cashier:'Marina Demo',...(i===7?{reversalReason:'Cambio de talle demo'}:{})};});
    const movements = [{id:'m0',date,kind:DemoMovementKind.Opening,amount:5000000,reason:'Fondo inicial demo'},...sales.filter(s=>s.method.isCash && s.status.canReverse).map(s=>({id:`m${s.id}`,date:s.date,kind:DemoMovementKind.Sale,amount:s.total,reason:`Venta ${s.id}`}))];
    this.state = Object.freeze({products:Object.freeze(products),customers:Object.freeze(customers),cart:[],customerId:'c0',discount:0,sales:Object.freeze(sales),movements:Object.freeze(movements),stockMovements:[],cashStatus:scenario.closed?DemoCashStatus.Closed:DemoCashStatus.Open,role:DemoRole.Manager,scenario});
    this.state=this.freeze({...this.state});this.listeners.forEach(listener=>listener(this.state));
  }
  setRole(role: DemoRole): void { this.editable(); this.publish({role}); }
  cart(productId: string, quantity: number): void {
    this.editable();
    const product=this.state.products.find(p=>p.id===productId);
    if (!product || !product.active) throw new Error('Producto no disponible.');
    if (!Number.isSafeInteger(quantity) || quantity<0 || quantity>product.stock) throw new Error('La cantidad debe ser entera y no superar el stock disponible.');
    const lines=this.state.cart.filter(l=>l.productId!==productId);
    if(quantity) lines.push({productId,quantity,version:product.version,price:product.price});
    this.publish({cart:Object.freeze(lines)});
  }
  clearCart(): void { this.editable(); this.publish({cart:[],discount:0,customerId:'c0'}); }
  selectCustomer(id: string): void { this.editable(); if(!this.state.customers.some(c=>c.id===id)) throw new Error('Cliente no disponible.'); this.publish({customerId:id}); }
  discount(percent: number): void { this.editable(); this.manager(); if(!Number.isSafeInteger(percent)||percent<0||percent>30) throw new Error('El descuento permitido es de 0 a 30 %.'); this.publish({discount:percent}); }
  total(): number { return Math.round(this.state.cart.reduce((sum,line)=>sum+line.price*line.quantity,0)*(100-this.state.discount)/100); }
  checkout(commandId: string, method: DemoPaymentMethod, received: number, reference: string): Sale {
    const existing=this.receipts.get(commandId); if(existing) return existing;
    if(!method.isCash) throw new Error('El pago electrónico requiere confirmación en el terminal simulado.');
    this.beginPayment(commandId,method,received,reference);
    return this.receipts.get(commandId)!;
  }
  beginPayment(commandId: string, method: DemoPaymentMethod, received: number, reference: string, cashAmount = 0): DemoPaymentAttempt {
    const existing=this.attempts.get(commandId); if(existing) return existing;
    this.editable();
    if(!commandId || method===DemoPaymentMethod.Unknown || this.state.role===DemoRole.Unknown) throw new Error('Elegí un medio de pago y perfil válido.');
    const total=this.total();
    new CheckoutFlow().validate({snapshot:this.state,method,received,total});
    this.cents(cashAmount);
    if(!method.isCash&&(cashAmount>=total||received<cashAmount)) throw new Error('La parte en efectivo debe ser menor al total y estar cubierta por el recibido.');
    if(this.state.scenario.fail&&!this.failed) { this.failed=true; throw new Error('No se pudo procesar el pago simulado. Reintentá: tu carrito sigue disponible.'); }
    const attempt:DemoPaymentAttempt=this.freeze({id:commandId,method,status:DemoPaymentStatus.Pending,total,cashAmount:method.isCash?total:cashAmount,received,reference:reference.trim()||`DEMO-${commandId.slice(0,8).toUpperCase()}`});
    this.attempts.set(commandId,attempt); this.publish({payment:attempt});
    return method.isCash?this.resolvePayment(commandId,DemoPaymentStatus.Approved):attempt;
  }
  resolvePayment(commandId: string, decision: DemoPaymentStatus): DemoPaymentAttempt {
    const attempt=this.attempts.get(commandId);
    if(!attempt) throw new Error('Intento de pago no disponible.');
    if(!attempt.status.pending) return attempt;
    if(this.state.payment?.id!==commandId||![DemoPaymentStatus.Approved,DemoPaymentStatus.Rejected,DemoPaymentStatus.Cancelled].includes(decision)) throw new Error('Elegí una resolución válida para el pago actual.');
    if(!decision.approved){const resolved=this.freeze({...attempt,status:decision});this.attempts.set(commandId,resolved);this.publish({payment:resolved});return resolved;}
    const {method,received,reference,total,cashAmount}=attempt;
    const lines=new CheckoutFlow().validate({snapshot:this.state,method,received,total});
    const customer=this.state.customers.find(c=>c.id===this.state.customerId)!;
    const sale:Sale=Object.freeze({id:`V-${this.id()}`,date:this.now(),customerId:customer.id,customerName:customer.name,lines:Object.freeze(lines),total,discount:this.state.discount,method,cashAmount,received:cashAmount?received:total,reference,status:DemoSaleStatus.Completed,cashier:this.state.role.label+' Demo'});
    const products=this.state.products.map(p=>({...p,stock:p.stock-(lines.find(l=>l.productId===p.id)?.quantity??0)}));
    const stockMovements=[...this.state.stockMovements,...lines.map(l=>({id:this.id(),productId:l.productId,date:sale.date,quantity:-l.quantity,reason:`Venta ${sale.id}`}))];
    const movements=cashAmount?[...this.state.movements,{id:this.id(),date:sale.date,kind:DemoMovementKind.Sale,amount:cashAmount,reason:`Venta ${sale.id}`}]:this.state.movements;
    const resolved=this.freeze({...attempt,status:decision,saleId:sale.id});this.attempts.set(commandId,resolved);
    this.receipts.set(commandId,sale); this.publish({payment:resolved,products:Object.freeze(products),stockMovements:Object.freeze(stockMovements),sales:Object.freeze([sale,...this.state.sales]),movements:Object.freeze(movements),cart:[],discount:0}); return resolved;
  }
  reverse(id:string,reason:string):void { this.manager(); const sale=this.state.sales.find(s=>s.id===id); if(!sale?.status.canReverse) throw new Error('La venta no admite otra devolución.'); if(!reason.trim()) throw new Error('Indicá el motivo de devolución.'); if(sale.cashAmount&&!this.state.cashStatus.canSell) throw new Error('Abrí la caja para registrar la devolución en efectivo.'); this.publish({sales:this.state.sales.map(s=>s.id===id?{...s,status:DemoSaleStatus.Reversed,reversalReason:reason}:s),products:this.state.products.map(p=>({...p,stock:p.stock+(sale.lines.find(l=>l.productId===p.id)?.quantity??0)})),stockMovements:[...this.state.stockMovements,...sale.lines.map(l=>({id:this.id(),productId:l.productId,date:this.now(),quantity:l.quantity,reason:`Devolución ${id}: ${reason}`}))],movements:sale.cashAmount?[...this.state.movements,{id:this.id(),date:this.now(),kind:DemoMovementKind.Refund,amount:sale.cashAmount,reason:`Devolución ${id}: ${reason}`}]:this.state.movements}); }
  saveProduct(product:Product):void { this.editable(); this.manager(); this.cents(product.price); if(!product.name.trim()||!product.sku.trim()||!product.category.trim()) throw new Error('Completá nombre, SKU y categoría.'); if(this.state.products.some(p=>p.id!==product.id&&p.sku.toLowerCase()===product.sku.toLowerCase())) throw new Error('Ya existe un producto con ese SKU.'); const previous=this.state.products.find(p=>p.id===product.id); const saved={...product,art:product.art,id:previous?.id??`p${this.id()}`,stock:previous?.stock??0,version:(previous?.version??0)+1}; this.publish({products:previous?this.state.products.map(p=>p.id===saved.id?saved:p):[...this.state.products,saved]}); }
  saveCustomer(customer:Customer):void { if(this.state.role===DemoRole.Unknown) throw new Error('Elegí un perfil válido.'); if(!customer.name.trim()||(customer.email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(customer.email))) throw new Error('Completá el nombre y un email válido si lo informás.'); if(customer.id==='c0') throw new Error('Cliente ocasional siempre disponible.'); const previous=this.state.customers.find(c=>c.id===customer.id); const saved={...customer,id:previous?.id??`c${this.id()}`}; this.publish({customers:previous?this.state.customers.map(c=>c.id===saved.id?saved:c):[...this.state.customers,saved]}); }
  adjust(productId:string,quantity:number,reason:string):void { this.editable(); this.manager(); const product=this.state.products.find(p=>p.id===productId); if(!product||!Number.isSafeInteger(quantity)||!quantity||product.stock+quantity<0||!reason.trim()) throw new Error('Ingresá un ajuste entero, un motivo y mantené el stock no negativo.'); this.publish({products:this.state.products.map(p=>p.id===productId?{...p,stock:p.stock+quantity}:p),stockMovements:[{id:this.id(),productId,date:this.now(),quantity,reason},...this.state.stockMovements]}); }
  openCash(amount:number):void { if(this.state.role===DemoRole.Unknown) throw new Error('Elegí un perfil válido.'); if(this.state.cashStatus!==DemoCashStatus.Closed) throw new Error('La caja ya está abierta o no está disponible.'); this.cents(amount); this.publish({cashStatus:DemoCashStatus.Open,movements:[...this.state.movements,{id:this.id(),date:this.now(),kind:DemoMovementKind.Opening,amount,reason:'Nueva apertura demo'}],cashCount:undefined}); }
  moveCash(kind:DemoMovementKind,amount:number,reason:string):void { this.manager(); if(!this.state.cashStatus.canSell) throw new Error('Abrí la caja para registrar movimientos.'); this.cents(amount); if(!amount||!reason.trim()||![DemoMovementKind.Income,DemoMovementKind.Expense].includes(kind)) throw new Error('Indicá importe, categoría y motivo.'); if(kind===DemoMovementKind.Expense&&amount>this.expectedCash()) throw new Error('El egreso supera el efectivo disponible.'); this.publish({movements:[...this.state.movements,{id:this.id(),date:this.now(),kind,amount,reason}]}); }
  expectedCash():number { const start=this.state.movements.map(m=>m.kind).lastIndexOf(DemoMovementKind.Opening);return this.state.movements.slice(Math.max(0,start)).reduce((sum,m)=>sum+m.amount*m.kind.sign,0); }
  closeCash(declared:number,reason:string):void { this.editable(); this.manager(); this.cents(declared); if(!this.state.cashStatus.canSell) throw new Error('La caja no está abierta.'); const expected=this.expectedCash(); if(declared!==expected&&!reason.trim()) throw new Error('Explicá la diferencia antes de cerrar.'); this.publish({cashStatus:DemoCashStatus.Closed,cashCount:{declared,expected,reason}}); }
}
