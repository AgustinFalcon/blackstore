import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { LoadCounterUseCase } from '../../application/load-counter.usecase';
import { PosStore } from '../../application/pos.store';
import { CatalogViewComponent, CatalogViewState } from './catalog.view';

@Component({
  selector: 'bs-catalog-page',
  standalone: true,
  imports: [CatalogViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-catalog-view [state]="state()" (retry)="reload()" />`,
})
export class CatalogContainerComponent implements OnInit {
  private readonly store = inject(PosStore);
  private readonly counter = inject(LoadCounterUseCase);
  readonly state = computed<CatalogViewState>(() => ({
    loading: this.store.counterLoading(),
    error: this.store.catalogError(),
    catalog: this.store.catalog(),
    saleDisabled: this.store.catalogClosed() || this.store.stockBlocked() || this.store.writesDisabled() || !this.store.openSession(),
  }));

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.counter.refresh();
  }
}
