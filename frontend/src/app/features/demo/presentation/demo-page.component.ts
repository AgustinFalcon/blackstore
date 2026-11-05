import { Component, inject, signal, OnDestroy, Injector, afterNextRender } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DemoRepository } from '../application/demo-repository';
import { Customer, DemoPaymentStatus, DemoProductArt, DemoStockFilter, DemoProductStatus, DemoCashStatus, DemoMovementKind, DemoPaymentMethod, DemoRole, DemoSaleStatus, DemoScenario, DemoViewState, Product, Sale, money } from '../domain/demo-types';
import { DemoDialogDirective } from './demo-dialog.directive';
import { DemoFeedbackComponent } from './demo-feedback.component';

import { DemoCashClosure, DemoCashShift } from '../domain/demo-types';
import { DemoCalendar, DemoDateRange } from '../domain/demo-calendar';
import { DemoQueries } from '../domain/demo-queries';
import { demoCsv } from '../infrastructure/demo-csv';
import { DemoPage } from '../domain/demo-page';
import { DemoWireMapper } from '../infrastructure/demo-wire-mapper';
import { DemoAction, DemoPermissionError } from '../domain/demo-permissions';
import { DemoMoney } from '../domain/demo-money';

type Draft<T> = {-readonly [K in keyof T]: T[K]};
@Component({selector:'bs-demo-page',standalone:true,imports:[FormsModule,RouterLink,DemoDialogDirective,DemoFeedbackComponent],templateUrl:'./demo-page.component.html',styleUrl:'./demo.css'})
export class DemoPageComponent implements OnDestroy {
 readonly repo=inject(DemoRepository); private readonly route=inject(ActivatedRoute); readonly router=inject(Router);
 private readonly injector=inject(Injector);
 readonly paymentStates=DemoPaymentStatus; readonly paymentTypes=DemoPaymentMethod;
 readonly actions=DemoAction;
 restriction(action:DemoAction):string{return this.repo.authorize(action).reason;}
 can(action:DemoAction):boolean{return this.repo.authorize(action).allowed;}
 require(action:DemoAction):void{const decision=this.repo.authorize(action);if(!decision.allowed)throw new DemoPermissionError(decision);}
 readonly pages=DemoPage; readonly page=DemoWireMapper.page(this.route.snapshot.paramMap.has('saleId')?'receipt':this.route.snapshot.url[0]?.path??'');
 readonly state=signal(this.repo.snapshot()); private readonly unsubscribe=this.repo.subscribe(s=>this.state.set(s));
 readonly money=money; readonly methods=DemoPaymentMethod.values; readonly saleStatuses=DemoSaleStatus.values; readonly scenarios=DemoScenario.values; readonly roles=DemoRole.values; readonly kinds=[DemoMovementKind.Income,DemoMovementKind.Expense];
 readonly notice=signal(''); readonly error=signal(''); readonly view=signal(DemoViewState.Ready);
 search=''; category=''; methodFilter:DemoPaymentMethod|null=null; statusFilter:DemoSaleStatus|null=null; from=this.route.snapshot.queryParamMap.get('from')??''; to=this.route.snapshot.queryParamMap.get('to')??''; pageNumber=1;
 readonly today=DemoCalendar.BuenosAires.day(new Date().toISOString())!; readonly todayParams={from:this.today,to:this.today};
 readonly stockFilters=DemoStockFilter;
 private stockFilter=DemoWireMapper.stockFilter(this.route.snapshot.queryParamMap.get('stock')??DemoStockFilter.All.key);
 get stockOnly():boolean{return this.stockFilter===DemoStockFilter.LowActive;}
 set stockOnly(value:boolean){this.stockFilter=value?DemoStockFilter.LowActive:DemoStockFilter.All;}
 selectedPayment=DemoPaymentMethod.Unknown; mixed=false; mixedCash=0; received=0; reference=''; reason=''; cashAmount=0; movementKind=DemoMovementKind.Expense; declared=0; selectedScenario=DemoScenario.Normal;
 productDraft:Draft<Product>|null=null; customerDraft:Draft<Customer>|null=null; selectedProduct:Product|null=null; selectedCustomer:Customer|null=null; adjustment=0;
 readonly Math=Math; readonly fieldErrors=signal<Readonly<Record<string,string>>>({}); readonly cashPrint=signal<DemoCashShift|DemoCashClosure|null>(null);
 fieldError(id:string):string{return this.fieldErrors()[id]??'';}
 private validate(checks:readonly (readonly [string,boolean,string])[]):boolean{const errors:Record<string,string>={};for(const [id,valid,message] of checks)if(!valid)errors[id]=message;this.fieldErrors.set(errors);const first=Object.keys(errors)[0];if(first){this.error.set('Revisá los campos indicados.');afterNextRender(()=>document.getElementById(first)?.focus(),{injector:this.injector});return false;}return true;}
 printCash(shift:DemoCashShift|DemoCashClosure):void{this.run(()=>{this.require(DemoAction.Print);this.cashPrint.set(shift);afterNextRender(()=>{window.print();},{injector:this.injector});},'Resumen de caja preparado.');}
 cashHistory(){return new DemoQueries(this.state()).cashHistory();}
 currentCashShift(){return new DemoQueries(this.state()).currentCashShift();}
 reportCategories():readonly string[]{return new DemoQueries(this.state()).categories(this.sales());}
 closedCount(shift:DemoCashShift|DemoCashClosure):DemoCashClosure|null{return 'declared' in shift?shift:null;}
 paymentMethod():DemoPaymentMethod{return this.selectedPayment;}
 changePayment(method:DemoPaymentMethod):void{this.selectedPayment=method;if(method.isCash){this.mixed=false;this.mixedCash=0;}this.fieldErrors.set({});}
 confirmation: {message:string;run:()=>void}|null=null; private slowTimer:ReturnType<typeof setTimeout>|undefined;
 constructor(){if(this.state().scenario.slow){this.view.set(DemoViewState.Loading);this.slowTimer=setTimeout(()=>this.view.set(DemoViewState.Ready),1200);} this.declared=this.repo.expectedCash()/100;}
 ngOnDestroy():void{this.unsubscribe();clearTimeout(this.slowTimer);}
 run(action:()=>void,message='Cambios guardados.'):void{this.error.set('');this.notice.set('');try{action();this.notice.set(message);}catch(error){this.error.set(error instanceof Error?error.message:'No se pudo completar la acción.');}}
 overlayActive():boolean{return !!(this.productDraft||this.customerDraft||this.selectedProduct||this.selectedCustomer||this.confirmation);}
 todaySales():readonly Sale[]{return new DemoQueries(this.state()).sales({search:'',method:null,status:null,period:DemoWireMapper.period(this.today,this.today)});}
 todayTotal():number{return new DemoQueries(this.state()).total(this.todaySales());}
 todayAverage():number{return new DemoQueries(this.state()).average(this.todaySales());}
 todayCompleted():number{return new DemoQueries(this.state()).completed(this.todaySales());}
 currentCashMovements(){return new DemoQueries(this.state()).currentCashMovements();}
 confirm(message:string,run:()=>void):void{this.confirmation={message,run};}
 accept():void{const action=this.confirmation?.run;this.confirmation=null;if(action)this.run(action);}
 categories():string[]{return [...new Set(this.state().products.map(p=>p.category))];}
 products():readonly Product[]{const query=this.search.trim().toLowerCase();return this.state().products.filter(p=>(!query||`${p.name} ${p.sku}`.toLowerCase().includes(query))&&(!this.category||p.category===this.category)&&this.stockFilter.matches(p));}
 customers():readonly Customer[]{return this.state().customers.filter(c=>`${c.name} ${c.email}`.toLowerCase().includes(this.search.toLowerCase()));}
 period():DemoDateRange{return DemoWireMapper.period(this.from,this.to);}
 rangeInvalid():boolean{return !this.period().isValid;}
 sales():readonly Sale[]{return new DemoQueries(this.state()).sales({search:this.search,method:this.methodFilter,status:this.statusFilter,period:this.period()});}
 visibleSales():readonly Sale[]{return this.sales().slice((this.pageNumber-1)*6,this.pageNumber*6);}
 salesTotal():number{return new DemoQueries(this.state()).total(this.sales());}
 reportExpenses():number{return new DemoQueries(this.state()).expenses(this.period());}
 reportByMethod(method:DemoPaymentMethod):number{return new DemoQueries(this.state()).byMethod(this.sales(),method);}
 reportByCategory(category:string):number{return new DemoQueries(this.state()).byCategory(this.sales(),category);}
 topProducts():{productId:string;sku:string;name:string;quantity:number}[]{return new DemoQueries(this.state()).topProducts(this.sales());}
 lowStock():number{return this.state().products.filter(p=>DemoStockFilter.LowActive.matches(p)).length;}
 completedSales():number{return new DemoQueries(this.state()).completed(this.sales());}
 averageTicket():number{return new DemoQueries(this.state()).average(this.sales());}
 cartUnits():number{return new DemoQueries(this.state()).cartUnits();}
 viewCart():void{const cart=document.getElementById('demo-cart');cart?.scrollIntoView({block:'start',behavior:'instant'});cart?.focus({preventScroll:true});}
 sale():Sale|undefined{return this.state().sales.find(s=>s.id===this.route.snapshot.paramMap.get('saleId'));}
 product(id:string):Product{return this.state().products.find(p=>p.id===id)!;}
 quantity(id:string):number{return this.state().cart.find(l=>l.productId===id)?.quantity??0;}
 add(product:Product):void{this.run(()=>this.repo.cart(product.id,this.quantity(product.id)+1),`${product.name} agregado al carrito.`);}
 changeQuantity(id:string,value:number):void{this.run(()=>this.repo.cart(id,Number(value)),'Carrito actualizado.');}
 changeQuantityBy(id:string,delta:number):void{this.changeQuantity(id,this.quantity(id)+delta);}
 clearFilters():void{this.search='';this.category='';this.stockOnly=false;this.from='';this.to='';this.methodFilter=null;this.statusFilter=null;this.pageNumber=1;}
 pay():void{if(!this.validate([['payment-method',this.selectedPayment!==DemoPaymentMethod.Unknown,'Elegí un medio de pago.'],['payment-received',!this.selectedPayment.isCash&&!this.mixed||Number.isFinite(this.received)&&this.received*100>=(this.mixed?this.mixedCash*100:this.repo.total()),'El efectivo recibido debe cubrir el importe en efectivo.'],['payment-mixed',!this.mixed||this.mixedCash>0&&this.mixedCash*100<this.repo.total(),'La parte en efectivo debe ser mayor a cero y menor al total.']]))return;this.run(()=>{const attempt=this.repo.beginPayment(crypto.randomUUID(),this.paymentMethod(),Math.round(this.received*100),this.reference,this.mixed?Math.round(this.mixedCash*100):0);if(attempt.saleId)void this.router.navigate(['/demo/ventas',attempt.saleId]);else this.focusPayment('demo-payment-terminal');},'');}
 resolvePayment(status:DemoPaymentStatus):void{this.run(()=>{const attempt=this.repo.resolvePayment(this.state().payment!.id,status);if(attempt.saleId)void this.router.navigate(['/demo/ventas',attempt.saleId]);else this.focusPayment('demo-payment-retry');},status.retryable?'':status.label);}
 private focusPayment(id:string):void{afterNextRender(()=>document.getElementById(id)?.focus(),{injector:this.injector});}
 cancelPayment():void{if(this.state().payment?.status.pending)this.resolvePayment(DemoPaymentStatus.Cancelled);void this.router.navigate(['/demo/pos']);}
 productArt(p:Product):string{return p.art.asset;}
 productTint(p:Product):string{return `hue-rotate(${this.state().products.indexOf(p)*23}deg)`;}
 editProduct(product?:Product):void{this.fieldErrors.set({});this.selectedProduct=null;this.productDraft=product?{...product}:{id:'',name:'',sku:'',category:this.categories()[0],art:DemoProductArt.Unknown,price:0,stock:0,status:DemoProductStatus.Active,version:1,icon:'◈'};}
 saveProduct():void{if(!this.validate([['product-name',!!this.productDraft?.name.trim(),'Ingresá un nombre.'],['product-sku',!!this.productDraft?.sku.trim(),'Ingresá un SKU.'],['product-price',!!this.productDraft&&Number.isSafeInteger(this.productDraft.price)&&this.productDraft.price>0,'Ingresá un precio mayor a cero.']]))return;this.run(()=>{this.repo.saveProduct(this.productDraft!);this.productDraft=null;},'Producto guardado en el catálogo demo.');}
 editCustomer(customer?:Customer):void{this.fieldErrors.set({});this.selectedCustomer=null;this.customerDraft=customer?{...customer}:{id:'',name:'',email:'',phone:''};}
 saveCustomer():void{if(!this.validate([['customer-name',!!this.customerDraft?.name.trim(),'Ingresá un nombre.'],['customer-email',!this.customerDraft?.email||/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.customerDraft.email),'Ingresá un email válido.']]))return;this.run(()=>{this.repo.saveCustomer(this.customerDraft!);this.customerDraft=null;},'Cliente guardado.');}
 customerSales(id:string):readonly Sale[]{return this.state().sales.filter(s=>s.customerId===id);}
 date(value:string):string{return DemoCalendar.BuenosAires.display(value);}
 download(name:string,content:string,type='text/plain'):void{this.require(DemoAction.Export);const url=URL.createObjectURL(new Blob([content],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),0);this.notice.set('Descarga preparada.');}
 csv(rows:readonly (readonly unknown[])[]):string{return demoCsv(rows);}
 exportSales():void{this.run(()=>this.download('ventas-demo.csv',this.csv([['Venta','Fecha','Cliente','Medio','Estado','Total ARS'],...this.sales().map(s=>[s.id,this.date(s.date),s.customerName,s.method.label,s.status.label,DemoMoney.decimal(s.total)])]),'text/csv;charset=utf-8'),'Descarga preparada.');}
 exportReport():void{this.run(()=>this.download('reporte-demo.csv',this.csv([['Desde',this.from||'Inicio'],['Hasta',this.to||'Hoy'],['Ventas netas ARS',DemoMoney.decimal(this.salesTotal())],['Egresos ARS',DemoMoney.decimal(this.reportExpenses())],...this.methods.map(m=>[m.label,DemoMoney.decimal(this.reportByMethod(m))])]),'text/csv;charset=utf-8'),'Descarga preparada.');}
 receiptLines(sale:Sale):readonly string[]{return ['BLACKSTORE · COMPROBANTE SIMULADO · NO FISCAL',sale.id,this.date(sale.date),'Cajero: '+sale.cashier,'Cliente: '+sale.customerName,...sale.lines.map(l=>l.quantity+' × '+l.name+' · '+l.sku+' · '+money(l.price)+' por unidad · '+money(l.price*l.quantity)),'Descuento '+sale.discount+'% · Total '+money(sale.total),'Pago aprobado · '+sale.method.label+' · Referencia: '+sale.reference,'Efectivo: '+money(sale.cashAmount)+(!sale.method.isCash?' · '+sale.method.label+': '+money(sale.total-sale.cashAmount):'')+' · Vuelto: '+money(sale.cashAmount?sale.received-sale.cashAmount:0),'Entrega inmediata en mostrador · sin envío',sale.status.label,sale.reversalReason?'Devolución registrada: '+sale.reversalReason+'. El comprobante original se conserva.':'','No es una factura fiscal.'];}
 downloadReceipt(sale:Sale):void{this.run(()=>this.download(sale.id+'-demo.txt',this.receiptLines(sale).join('\n')),'Descarga preparada.');}
 print():void{this.run(()=>{this.require(DemoAction.Print);window.print();},'Impresión preparada.');}
 reset():void{this.confirm('Se reemplazarán todos los datos y el carrito demo. ¿Continuar?',()=>{this.repo.reset(this.selectedScenario);this.notice.set('Escenario cargado.');void this.router.navigate(['/demo']);});}
 closeOverlay():void{this.fieldErrors.set({});this.productDraft=null;this.customerDraft=null;this.selectedProduct=null;this.selectedCustomer=null;this.confirmation=null;}
 toggleProduct(p:Product):void{this.run(()=>this.repo.saveProduct({...p,status:p.status.toggled}));}
 selectCustomer(id:string):void{this.run(()=>this.repo.selectCustomer(id),'Cliente seleccionado.');}
 applyDiscount(value:number):void{this.run(()=>this.repo.discount(Number(value)),'Descuento aplicado.');}
 emptyCart():void{this.confirm('¿Vaciar el carrito?',()=>this.repo.clearCart());}
 reverseSale(s:Sale):void{this.confirm('¿Registrar devolución y restituir el stock de esta venta?',()=>this.repo.reverse(s.id,this.reason));}
 openCash():void{if(!this.validate([['cash-opening',Number.isFinite(this.cashAmount)&&this.cashAmount>=0,'Ingresá un fondo inicial válido.']]))return;this.run(()=>this.repo.openCash(Math.round(this.cashAmount*100)),'Caja abierta.');}
 moveCash():void{if(!this.validate([['cash-amount',Number.isFinite(this.cashAmount)&&this.cashAmount>0,'Ingresá un importe mayor a cero.'],['cash-reason',!!this.reason.trim(),'Ingresá un motivo.']]))return;this.run(()=>this.repo.moveCash(this.movementKind,Math.round(this.cashAmount*100),this.reason),'Movimiento registrado.');}
 closeCash():void{if(!this.validate([['cash-declared',Number.isFinite(this.declared)&&this.declared>=0,'Ingresá un importe declarado válido.'],['cash-difference',Math.round(this.declared*100)===this.repo.expectedCash()||!!this.reference.trim(),'Explicá la diferencia de caja.']]))return;this.confirm('¿Cerrar el turno con este arqueo?',()=>this.repo.closeCash(Math.round(this.declared*100),this.reference));}
 changeRole(role:DemoRole):void{this.run(()=>this.repo.setRole(role),'Perfil ficticio actualizado.');}
 adjustStock(p:Product):void{if(!this.validate([['stock-adjustment',Number.isSafeInteger(this.adjustment)&&this.adjustment!==0,'Ingresá una cantidad entera distinta de cero.'],['stock-reason',!!this.reason.trim(),'Ingresá un motivo.']]))return;this.run(()=>this.repo.adjust(p.id,this.adjustment,this.reason),'Ajuste registrado.');}
 saleForCustomer(c:Customer):void{this.selectCustomer(c.id);this.closeOverlay();void this.router.navigate(['/demo/pos']);}
}
