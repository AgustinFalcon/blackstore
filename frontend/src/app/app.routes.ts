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
import { SaleTicketSnapshotComponent } from './features/sales/sale-ticket-snapshot.component';
import { CashPageMode } from './core/domain/cash-page-mode';

export const routes: Routes = [
  { path: 'sesion', component: SessionComponent, title: 'Sesión · BlackStore POS' },
  { path: '', component: PosShellComponent, title: 'Inicio · BlackStore POS', canActivate: [sessionGuard], data: { permission: StaffPermission.WorkspaceRead } },
  { path: 'caja/cierre', component: CashSessionComponent, canActivate: [sessionGuard], data: {
    permission: StaffPermission.CashSessionClose, cashPageMode: CashPageMode.Close.wire,
  }, title: 'Cerrar caja · BlackStore POS' },
  { path: 'caja', component: CashSessionComponent, canActivate: [sessionGuard], data: {
    permission: StaffPermission.CashSessionList, cashPageMode: CashPageMode.Workspace.wire,
  }, title: 'Caja · BlackStore POS' },
  { path: 'catalogo', component: CatalogPanelComponent, title: 'Catálogo · BlackStore POS', canActivate: [sessionGuard], data: { permission: StaffPermission.CatalogRead } },
  { path: 'ticket/:saleId', component: SaleTicketSnapshotComponent, title: 'Comprobante · BlackStore POS', canActivate: [sessionGuard], data: { permission: StaffPermission.SaleRead } },
  { path: 'ticket', component: SaleTicketComponent, title: 'Nueva venta · BlackStore POS', canActivate: [sessionGuard], data: { permission: StaffPermission.SaleReserve } },
  { path: 'ventas', component: DurableSalesComponent, title: 'Ventas · BlackStore POS', canActivate: [sessionGuard], data: { permission: StaffPermission.SaleRead } },
  { path: 'reportes', component: ShiftReportComponent, title: 'Reportes · BlackStore POS', canActivate: [sessionGuard], data: { permission: StaffPermission.ShiftReportRead } },
  { path: '**', redirectTo: '' },
];
