import { Routes } from '@angular/router';
import { DemoRepository } from '../application/demo-repository';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';
import { DemoShellComponent } from './demo-shell.component';
import { DemoPageComponent } from './demo-page.component';
export const demoRoutes: Routes = [{path:'',component:DemoShellComponent,providers:[{provide:DemoRepository,useFactory:()=>new LocalDemoRepository()}],children:[
  ...[['','Inicio'],['pos','Nueva venta'],['pago','Cobro'],['productos','Productos'],['inventario','Inventario'],['clientes','Clientes'],['ventas','Ventas'],['caja','Caja'],['reportes','Reportes'],['escenarios','Escenarios']].map(([path,label])=>({path,component:DemoPageComponent,title:`${label} · BlackStore Demo`})),
  {path:'ventas/:saleId',component:DemoPageComponent,title:'Comprobante · BlackStore Demo'},
  {path:'**',redirectTo:''}
]}];
