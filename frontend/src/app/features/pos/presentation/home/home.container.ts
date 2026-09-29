import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { PosSessionStore } from '../../../../core/session/pos-session.store';
import { roleCapabilities } from '../../../../core/session/staff-role';
import { LoadCounterUseCase } from '../../application/load-counter.usecase';
import { PosStore } from '../../application/pos.store';
import { HomeViewComponent, HomeViewState } from './home.view';

@Component({
  selector: 'bs-home-page',
  standalone: true,
  imports: [HomeViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-home-view [state]="state()" (retry)="reload()" />`,
})
export class HomeContainerComponent implements OnInit {
  private readonly store = inject(PosStore);
  private readonly session = inject(PosSessionStore);
  private readonly counter = inject(LoadCounterUseCase);

  readonly state = computed<HomeViewState>(() => {
    const actor = this.session.actor();
    const caps = roleCapabilities(actor?.role ?? null);
    const catalog = this.store.catalog();
    const open = this.store.openSession();
    const unavailable = this.store.catalogUnavailable() || !catalog;
    return {
      loading: this.store.counterLoading(),
      signedIn: actor !== null,
      denial: this.session.denial(),
      catalogVersion: catalog?.version ?? 'sin versión',
      catalogBadge: unavailable ? 'no disponible' : catalog.stale || catalog.items.length === 0 ? 'vencido' : 'vigente',
      catalogOk: !unavailable && !catalog.stale && catalog.items.length > 0,
      sessionText: open ? `Caja abierta · sesión ${open.id} · terminal ${open.terminalId}` : null,
      persistence: this.store.workspace()?.persistence ?? '—',
      blockReason: this.store.freshSaleBlock(),
      ticketDisabled: !caps.sell || this.store.openSession() === null,
      canCash: caps.cash,
      canReports: caps.reports,
      canClose: caps.close,
      error: this.store.catalogError() ?? this.store.sessionsError(),
    };
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.counter.refresh();
  }
}
