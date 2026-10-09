import { Routes } from '@angular/router';
import { sessionGuard } from './core/services/session.guard';
import { StaffPermission } from './core/domain/session-types';
import { SessionComponent } from './features/session/session.component';
import { PosShellComponent } from './features/pos/shell/pos-shell.component';
import { CashSessionComponent } from './features/cash/cash-session.component';
import { CatalogPanelComponent } from './features/catalog/catalog-panel.component';
import { SaleTicketComponent } from './features/sales/sale-ticket.component';
import { ShiftReportComponent } from './features/reports/shift-report.component';
import { DurableSalesComponent } from './features/sales/durable-sales.component';

export const routes: Routes = [
  { path: 'demo', loadChildren: () => import('./features/demo/presentation/demo.routes').then(module => module.demoRoutes) },
  { path: 'sesion', component: SessionComponent },
  { path: '', component: PosShellComponent, canActivate: [sessionGuard], data: { permission: StaffPermission.WorkspaceRead } },
  { path: 'caja', component: CashSessionComponent, canActivate: [sessionGuard], data: { permission: StaffPermission.CashSessionList } },
  { path: 'catalogo', component: CatalogPanelComponent, canActivate: [sessionGuard], data: { permission: StaffPermission.CatalogRead } },
  { path: 'ticket', component: SaleTicketComponent, canActivate: [sessionGuard], data: { permission: StaffPermission.SaleReserve } },
  { path: 'ventas', component: DurableSalesComponent, canActivate: [sessionGuard], data: { permission: StaffPermission.SaleRead } },
  { path: 'reportes', component: ShiftReportComponent, canActivate: [sessionGuard], data: { permission: StaffPermission.ShiftReportRead } },
  { path: '**', redirectTo: '' },
];
