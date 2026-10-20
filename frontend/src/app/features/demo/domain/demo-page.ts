export class DemoPage {
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
 static readonly values=Object.freeze([this.Home,this.Pos,this.Payment,this.Products,this.Inventory,this.Customers,this.Sales,this.Receipt,this.Cash,this.Reports,this.Scenarios]);
 static fromWire(key:unknown):DemoPage{return this.values.find(p=>p.key===key)??this.Unknown;}
}
