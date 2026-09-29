import { Routes } from '@angular/router';
import { cashGuard, closeGuard, reportsGuard, sellGuard, sessionGuard } from './core/session/pos.guards';
import { CashCloseContainerComponent } from './features/pos/presentation/cash-close/cash-close.container';
import { CashContainerComponent } from './features/pos/presentation/cash/cash.container';
import { CatalogContainerComponent } from './features/pos/presentation/catalog/catalog.container';
import { HomeContainerComponent } from './features/pos/presentation/home/home.container';
import { ReportsContainerComponent } from './features/pos/presentation/reports/reports.container';
import { SessionContainerComponent } from './features/pos/presentation/session/session.container';
import { TicketReadContainerComponent } from './features/pos/presentation/ticket-read/ticket-read.container';
import { TicketContainerComponent } from './features/pos/presentation/ticket/ticket.container';

export const routes: Routes = [
  { path: 'sesion', component: SessionContainerComponent },
  { path: '', canActivate: [sessionGuard], component: HomeContainerComponent },
  { path: 'caja/cierre', canActivate: [closeGuard], component: CashCloseContainerComponent },
  { path: 'caja', canActivate: [cashGuard], component: CashContainerComponent },
  { path: 'catalogo', canActivate: [sessionGuard], component: CatalogContainerComponent },
  { path: 'ticket/:saleId', canActivate: [sessionGuard], component: TicketReadContainerComponent },
  { path: 'ticket', canActivate: [sellGuard], component: TicketContainerComponent },
  { path: 'reportes', canActivate: [reportsGuard], component: ReportsContainerComponent },
  { path: '**', redirectTo: '' },
];
