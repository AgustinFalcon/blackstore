import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { LoadReportsUseCase } from '../../application/load-reports.usecase';
import { PosStore } from '../../application/pos.store';
import { reportHasMovement } from '../../domain/sale-block';
import { ReportsViewComponent, ReportsViewState } from './reports.view';

@Component({
  selector: 'bs-reports-page',
  standalone: true,
  imports: [ReportsViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-reports-view [state]="state()" (retry)="reload()" />`,
})
export class ReportsContainerComponent implements OnInit {
  private readonly store = inject(PosStore);
  private readonly reports = inject(LoadReportsUseCase);
  readonly state = computed<ReportsViewState>(() => {
    const shift = this.store.shiftReport();
    const daily = this.store.dailyReport();
    return {
      loading: this.store.reportsLoading(),
      error: this.store.reportsError(),
      dailyError: this.store.dailyError(),
      shift,
      daily,
      shiftEmpty: shift !== null && !reportHasMovement(shift),
      dailyEmpty: daily !== null && !reportHasMovement(daily),
    };
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.reports.refresh();
  }
}
