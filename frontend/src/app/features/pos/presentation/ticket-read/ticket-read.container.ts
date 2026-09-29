import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { SaleTicketUseCase } from '../../application/sale-ticket.usecase';
import { PosStore } from '../../application/pos.store';
import { TicketReadViewComponent, TicketReadViewState } from './ticket-read.view';

@Component({
  selector: 'bs-ticket-read-page',
  standalone: true,
  imports: [TicketReadViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-ticket-read-view [state]="state()" (retry)="retry()" />`,
})
export class TicketReadContainerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly sales = inject(SaleTicketUseCase);
  private readonly store = inject(PosStore);
  private saleId = '';

  readonly state = computed<TicketReadViewState>(() => ({
    loading: this.store.readLoading(),
    missing: this.store.readMissing(),
    error: this.store.readError(),
    sale: this.store.readSale(),
    saleId: this.saleId,
  }));

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.saleId = params.get('saleId') ?? '';
      this.sales.loadSnapshot(this.saleId);
    });
  }

  retry(): void {
    this.sales.loadSnapshot(this.saleId);
  }
}
