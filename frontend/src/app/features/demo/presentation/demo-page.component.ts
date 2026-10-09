import { Component, inject, signal, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DemoRepository } from '../application/demo-repository';
import { Customer, DemoCashStatus, DemoMovementKind, DemoPaymentMethod, DemoRole, DemoSaleStatus, DemoScenario, DemoViewState, Product, Sale, money } from '../domain/demo-types';
import { DemoDialogDirective } from './demo-dialog.directive';
class DemoPage {
 private constructor(readonly key:string,readonly title:string,readonly subtitle:string){}
 static readonly Home=new DemoPage('','Buen día, Marina ☀','Así viene tu comercio. Todo listo para una nueva venta.');
 static readonly Pos=new DemoPage('pos','Nueva venta','Buscá, agregá productos y armá el carrito de tu cliente.');
 static readonly Payment=new DemoPage('pago','Cobrar venta','Elegí cómo paga tu cliente y confirmá el resumen.');
 static readonly Products=new DemoPage('productos','Productos','Tu catálogo de demostración, siempre a mano.');
 static readonly Inventory=new DemoPage('inventario','Inventario','Existencias y movimientos simulados. No sincroniza stock externo.');
 static readonly Customers=new DemoPage('clientes','Clientes','Conocé a quienes eligen tu comercio. Datos ficticios.');
 static readonly Sales=new DemoPage('ventas','Ventas','Consultá ventas, comprobantes y devoluciones.');
 static readonly Receipt=new DemoPage('receipt','Comprobante de venta','Comprobante comercial simulado. No es una factura fiscal.');
 static readonly Cash=new DemoPage('caja','Caja','Controlá el efectivo, los movimientos y el cierre del turno.');
 static readonly Reports=new DemoPage('reportes','Reportes','Entendé tus ventas y tomá mejores decisiones.');
 static readonly Scenarios=new DemoPage('escenarios','Escenarios de demostración','Probá estados del comercio y perfiles ficticios.');
 static readonly Unknown=new DemoPage('unknown','Pantalla no disponible','Volvé al inicio para continuar.');
 static fromWire(key:unknown):DemoPage{return [this.Home,this.Pos,this.Payment,this.Products,this.Inventory,this.Customers,this.Sales,this.Receipt,this.Cash,this.Reports,this.Scenarios].find(p=>p.key===key)??this.Unknown;}
}
type Draft<T> = {-readonly [K in keyof T]: T[K]};
@Component({selector:'bs-demo-page',standalone:true,imports:[FormsModule,RouterLink,DemoDialogDirective],templateUrl:'./demo-page.component.html',styleUrl:'./demo.css'})
export class DemoPageComponent implements OnDestroy {
 readonly repo=inject(DemoRepository); private readonly route=inject(ActivatedRoute); readonly router=inject(Router);
 readonly pages=DemoPage; readonly page=DemoPage.fromWire(this.route.snapshot.paramMap.has('saleId')?'receipt':this.route.snapshot.url[0]?.path??'');
 readonly state=signal(this.repo.snapshot()); private readonly unsubscribe=this.repo.subscribe(s=>this.state.set(s));
 readonly money=money; readonly methods=DemoPaymentMethod.values; readonly saleStatuses=DemoSaleStatus.values; readonly scenarios=DemoScenario.values; readonly roles=DemoRole.values; readonly kinds=[DemoMovementKind.Income,DemoMovementKind.Expense];
 readonly notice=signal(''); readonly error=signal(''); readonly view=signal(DemoViewState.Ready);
 search=''; category=''; stockOnly=false; methodKey=''; statusKey=''; from=''; to=''; pageNumber=1;
 paymentKey=''; received=0; reference=''; discountValue=0; reason=''; cashAmount=0; moveKey=DemoMovementKind.Expense.key; declared=0; scenarioKey=DemoScenario.Normal.key;
 productDraft:Draft<Product>|null=null; customerDraft:Draft<Customer>|null=null; selectedProduct:Product|null=null; selectedCustomer:Customer|null=null; adjustment=0;
 readonly Math=Math;
 paymentMethod():DemoPaymentMethod{return DemoPaymentMethod.fromWire(this.paymentKey);}
 confirmation: {message:string;run:()=>void}|null=null; private readonly commandId=crypto.randomUUID(); private slowTimer:ReturnType<typeof setTimeout>|undefined;
 constructor(){if(this.state().scenario.slow){this.view.set(DemoViewState.Loading);this.slowTimer=setTimeout(()=>this.view.set(DemoViewState.Ready),1200);} this.declared=this.repo.expectedCash();}
 ngOnDestroy():void{this.unsubscribe();clearTimeout(this.slowTimer);}
 run(action:()=>void,message='Cambios guardados.'):void{this.error.set('');try{action();this.notice.set(message);}catch(error){this.error.set(error instanceof Error?error.message:'No se pudo completar la acción.');}}
 confirm(message:string,run:()=>void):void{this.confirmation={message,run};}
 accept():void{const action=this.confirmation?.run;this.confirmation=null;if(action)this.run(action);}
 categories():string[]{return [...new Set(this.state().products.map(p=>p.category))];}
 products():readonly Product[]{const query=this.search.trim().toLowerCase();return this.state().products.filter(p=>(!query||`${p.name} ${p.sku}`.toLowerCase().includes(query))&&(!this.category||p.category===this.category)&&(!this.stockOnly||p.stock<=3));}
 customers():readonly Customer[]{return this.state().customers.filter(c=>`${c.name} ${c.email}`.toLowerCase().includes(this.search.toLowerCase()));}
 sales():readonly Sale[]{return this.state().sales.filter(s=>(`${s.id} ${s.customerName}`.toLowerCase().includes(this.search.toLowerCase()))&&(!this.methodKey||s.method===DemoPaymentMethod.fromWire(this.methodKey))&&(!this.statusKey||s.status===DemoSaleStatus.fromWire(this.statusKey))&&(!this.from||s.date.slice(0,10)>=this.from)&&(!this.to||s.date.slice(0,10)<=this.to));}
 visibleSales():readonly Sale[]{return this.sales().slice((this.pageNumber-1)*6,this.pageNumber*6);}
 salesTotal():number{return this.sales().reduce((sum,s)=>sum+(s.status.canReverse?s.total:0),0);}
 reportExpenses():number{return this.state().movements.filter(m=>m.kind===DemoMovementKind.Expense&&(!this.from||m.date.slice(0,10)>=this.from)&&(!this.to||m.date.slice(0,10)<=this.to)).reduce((sum,m)=>sum+m.amount,0);}
 reportByMethod(method:DemoPaymentMethod):number{return this.sales().filter(s=>s.method===method&&s.status.canReverse).reduce((sum,s)=>sum+s.total,0);}
 reportByCategory(category:string):number{return this.sales().filter(s=>s.status.canReverse).reduce((sum,s)=>sum+s.lines.filter(l=>l.category===category).reduce((part,l)=>part+l.price*l.quantity*(100-s.discount)/100,0),0);}
 topProducts():{name:string;quantity:number}[]{const items=new Map<string,number>();this.sales().filter(s=>s.status.canReverse).forEach(s=>s.lines.forEach(l=>items.set(l.name,(items.get(l.name)??0)+l.quantity)));return [...items].map(([name,quantity])=>({name,quantity})).sort((a,b)=>b.quantity-a.quantity).slice(0,5);}
 lowStock():number{return this.state().products.filter(p=>p.stock<=3&&p.active).length;}
 completedSales():number{return this.sales().filter(s=>s.status.canReverse).length;}
 averageTicket():number{return this.completedSales()?Math.round(this.salesTotal()/this.completedSales()):0;}
 sale():Sale|undefined{return this.state().sales.find(s=>s.id===this.route.snapshot.paramMap.get('saleId'));}
 product(id:string):Product{return this.state().products.find(p=>p.id===id)!;}
 quantity(id:string):number{return this.state().cart.find(l=>l.productId===id)?.quantity??0;}
 add(product:Product):void{this.run(()=>this.repo.cart(product.id,this.quantity(product.id)+1),`${product.name} agregado al carrito.`);}
 changeQuantity(id:string,value:number):void{this.run(()=>this.repo.cart(id,Number(value)),'Carrito actualizado.');}
 clearFilters():void{this.search='';this.category='';this.stockOnly=false;this.from='';this.to='';this.methodKey='';this.statusKey='';this.pageNumber=1;}
 pay():void{this.run(()=>{const sale=this.repo.checkout(this.commandId,DemoPaymentMethod.fromWire(this.paymentKey),Math.round(this.received*100),this.reference);void this.router.navigate(['/demo/ventas',sale.id]);},'Venta registrada.');}
 editProduct(product?:Product):void{this.selectedProduct=null;this.productDraft=product?{...product}:{id:'',name:'',sku:'',category:this.categories()[0],price:0,stock:0,active:true,version:1,icon:'◈'};}
 saveProduct():void{this.run(()=>{this.repo.saveProduct(this.productDraft!);this.productDraft=null;},'Producto guardado en el catálogo demo.');}
 editCustomer(customer?:Customer):void{this.selectedCustomer=null;this.customerDraft=customer?{...customer}:{id:'',name:'',email:'',phone:''};}
 saveCustomer():void{this.run(()=>{this.repo.saveCustomer(this.customerDraft!);this.customerDraft=null;},'Cliente guardado.');}
 customerSales(id:string):readonly Sale[]{return this.state().sales.filter(s=>s.customerId===id);}
 date(value:string):string{return new Date(value).toLocaleString('es-AR',{dateStyle:'short',timeStyle:'short'});}
 download(name:string,content:string,type='text/plain'):void{const url=URL.createObjectURL(new Blob([content],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),0);this.notice.set('Descarga preparada.');}
 csv(rows:readonly (readonly unknown[])[]):string{return rows.map(row=>row.map(value=>{let text=String(value??'');if(/^[=+@\-]/.test(text))text="'"+text;return '"'+text.replace(/"/g,'""')+'"';}).join(';')).join('\r\n');}
 exportSales():void{this.download('ventas-demo.csv',this.csv([['Venta','Fecha','Cliente','Medio','Estado','Total ARS'],...this.sales().map(s=>[s.id,this.date(s.date),s.customerName,s.method.label,s.status.label,s.total/100])]),'text/csv;charset=utf-8');}
 exportReport():void{this.download('reporte-demo.csv',this.csv([['Desde',this.from||'Inicio'],['Hasta',this.to||'Hoy'],['Ventas netas ARS',this.salesTotal()/100],['Egresos ARS',this.reportExpenses()/100],...this.methods.map(m=>[m.label,this.reportByMethod(m)/100])]),'text/csv;charset=utf-8');}
 downloadReceipt(sale:Sale):void{this.download(`${sale.id}-demo.txt`,['BLACKSTORE · COMPROBANTE SIMULADO · NO FISCAL',sale.id,this.date(sale.date),sale.customerName,...sale.lines.map(l=>`${l.quantity} × ${l.name} ${money(l.price*l.quantity)}`),`Descuento ${sale.discount}%`,`Total ${money(sale.total)}`,sale.method.label,`Vuelto ${money(sale.method.isCash?sale.received-sale.total:0)}`,sale.status.label,sale.reversalReason??''].join('\n'));}
 print():void{window.print();}
 reset():void{this.confirm('Se reemplazarán todos los datos y el carrito demo. ¿Continuar?',()=>{this.repo.reset(DemoScenario.fromWire(this.scenarioKey));this.notice.set('Escenario cargado.');void this.router.navigate(['/demo']);});}
 closeOverlay():void{this.productDraft=null;this.customerDraft=null;this.selectedProduct=null;this.selectedCustomer=null;this.confirmation=null;}
 toggleProduct(p:Product):void{this.run(()=>this.repo.saveProduct({...p,active:!p.active}));}
 selectCustomer(id:string):void{this.run(()=>this.repo.selectCustomer(id),'Cliente seleccionado.');}
 applyDiscount():void{this.run(()=>this.repo.discount(this.discountValue),'Descuento aplicado.');}
 emptyCart():void{this.confirm('¿Vaciar el carrito?',()=>this.repo.clearCart());}
 reverseSale(s:Sale):void{this.confirm('¿Registrar devolución y restituir el stock de esta venta?',()=>this.repo.reverse(s.id,this.reason));}
 openCash():void{this.run(()=>this.repo.openCash(Math.round(this.cashAmount*100)),'Caja abierta.');}
 moveCash():void{this.run(()=>this.repo.moveCash(DemoMovementKind.fromWire(this.moveKey),Math.round(this.cashAmount*100),this.reason),'Movimiento registrado.');}
 closeCash():void{this.confirm('¿Cerrar el turno con este arqueo?',()=>this.repo.closeCash(Math.round(this.declared*100),this.reference));}
 changeRole(key:string):void{this.run(()=>this.repo.setRole(DemoRole.fromWire(key)),'Perfil ficticio actualizado.');}
 adjustStock(p:Product):void{this.run(()=>this.repo.adjust(p.id,this.adjustment,this.reason),'Ajuste registrado.');}
 saleForCustomer(c:Customer):void{this.selectCustomer(c.id);this.closeOverlay();void this.router.navigate(['/demo/pos']);}
}
