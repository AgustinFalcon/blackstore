import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { formatEfectivo } from '../../domain/money';
import { ShiftReport } from '../../domain/pos.models';

export interface ReportsViewState {
  readonly loading: boolean;
  readonly error: string | null;
  readonly dailyError: string | null;
  readonly shift: ShiftReport | null;
  readonly daily: ShiftReport | null;
  readonly shiftEmpty: boolean;
  readonly dailyEmpty: boolean;
}

@Component({
  selector: 'bs-reports-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reports.view.html',
})
export class ReportsViewComponent {
  readonly state = input.required<ReportsViewState>();
  readonly retry = output<void>();
  readonly format = formatEfectivo;

  fiscalLabel(fiscalResult: boolean): string {
    return fiscalResult ? 'dato marcado, sin emisión' : 'no es resultado fiscal';
  }
}
