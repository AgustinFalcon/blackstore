import { Routes } from '@angular/router';
import { PosShellComponent } from './features/pos/shell/pos-shell.component';
import { CashSessionComponent } from './features/cash/cash-session.component';
import { CatalogPanelComponent } from './features/catalog/catalog-panel.component';
import { SaleTicketComponent } from './features/sales/sale-ticket.component';
import { ShiftReportComponent } from './features/reports/shift-report.component';

export const routes: Routes = [
  { path: '', component: PosShellComponent },
  { path: 'caja', component: CashSessionComponent },
  { path: 'catalogo', component: CatalogPanelComponent },
  { path: 'ticket', component: SaleTicketComponent },
  { path: 'reportes', component: ShiftReportComponent },
  { path: '**', redirectTo: '' },
];
