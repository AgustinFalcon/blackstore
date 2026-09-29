import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { LoadCounterUseCase } from '../../application/load-counter.usecase';
import { PosStore } from '../../application/pos.store';
import { ReserveRequest, TicketViewComponent, TicketViewState } from './ticket.view';
import { SaleTicketUseCase } from '../../application/sale-ticket.usecase';
import { SaleStatus } from '../../domain/closed-status';

@Component({
  selector: 'bs-ticket-page',
  standalone: true,
  imports: [TicketViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bs-ticket-view
      [state]="state()"
      (reserve)="reserve($event)"
      (retryPay)="retryPay($event)"
      (refresh)="sales.refresh()"
      (commit)="sales.commit()"
      (release)="sales.release()"
      (reverse)="sales.reverse($event.paymentId, $event.reason)"
      (dismissPrompt)="store.clearTicketPrompt()"
      (goCatalog)="catalog()"
    />
  `,
})
export class TicketContainerComponent implements OnInit {
  readonly store = inject(PosStore);
  readonly sales = inject(SaleTicketUseCase);
  private readonly counter = inject(LoadCounterUseCase);
  private readonly router = inject(Router);

  readonly state = computed<TicketViewState>(() => {
    const sale = this.store.activeSale();
    const status = sale?.status ?? null;
    const retry = this.store.stockBlocked() && sale !== null && !this.store.catalogClosed();
    return {
      loading: this.store.counterLoading(),
      items: this.store.catalog()?.items ?? [],
      blockReason: this.store.freshSaleBlock(),
      reserveDisabled: this.store.reserveDisabled(),
      reserveLabel: retry ? 'Reintentar con otra operación' : 'Reservar y cobrar',
      sessionId: this.store.openSession()?.id ?? null,
      sale,
      busy: this.store.ticketBusy(),
      error: this.store.ticketError(),
      message: this.store.ticketMessage(),
      prompt: this.store.ticketPrompt(),
      writesDisabled: this.store.writesDisabled(),
      canCommit: status?.canFinish ?? false,
      canRelease: status?.canFinish ?? false,
      canRetryPay: status === SaleStatus.Reserved && (sale?.payments.length ?? 0) === 0,
    };
  });

  ngOnInit(): void {
    this.counter.refresh();
  }

  reserve(input: ReserveRequest): void {
    this.sales.reserve(input);
  }

  retryPay(input: ReserveRequest): void {
    this.sales.retryPayments(input);
  }

  catalog(): void {
    void this.router.navigate(['/catalogo']);
  }
}
