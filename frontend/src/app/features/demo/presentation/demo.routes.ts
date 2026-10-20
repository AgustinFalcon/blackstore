import { Routes } from '@angular/router';
import { DemoRepository } from '../application/demo-repository';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';
import { DemoShellComponent } from './demo-shell.component';
import { DemoPageComponent } from './demo-page.component';
export const demoRoutes: Routes = [{path:'',component:DemoShellComponent,providers:[{provide:DemoRepository,useFactory:()=>new LocalDemoRepository()}],children:[
  ...['','pos','pago','productos','inventario','clientes','ventas','caja','reportes','escenarios'].map(path=>({path,component:DemoPageComponent})),
  {path:'ventas/:saleId',component:DemoPageComponent},
  {path:'**',redirectTo:''}
]}];
