import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { formatEfectivo } from '../../domain/money';
import { CashSession } from '../../domain/pos.models';
import { PosDialogComponent } from '../dialog/pos-dialog.component';

export interface DrawerLine {
  readonly label: string;
  readonly amount: number;
}

export interface CashCloseViewState {
  readonly loading: boolean;
  readonly busy: boolean;
  readonly error: string | null;
  readonly notice: string | null;
  readonly session: CashSession | null;
  readonly expected: number;
  readonly cashIn: number;
  readonly expenseTotal: number;
  readonly movements: readonly DrawerLine[];
  readonly closed: boolean;
  readonly writesDisabled: boolean;
}

@Component({
  selector: 'bs-cash-close-view',
  standalone: true,
  imports: [FormsModule, RouterLink, PosDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cash-close.view.html',
})
export class CashCloseViewComponent {
  readonly state = input.required<CashCloseViewState>();
  readonly retry = output<void>();
  readonly closeSession = output<{ readonly declared: number; readonly reason: string }>();
  readonly format = formatEfectivo;
  readonly dialog = signal<'close' | null>(null);
  declared = 0;
  reason = '';

  difference(): number {
    const session = this.state().session;
    const declared = this.state().closed ? (session?.closingCashDeclared ?? 0) : Number(this.declared);
    return declared - this.state().expected;
  }

  canClose(): boolean {
    return this.reason.trim().length > 0 && Number.isFinite(Number(this.declared)) && Number(this.declared) >= 0 && !this.state().closed;
  }

  submit(): void {
    if (!this.canClose() || this.state().writesDisabled) return;
    this.dialog.set('close');
  }

  confirmClose(): void {
    if (!this.canClose() || this.state().writesDisabled || this.state().busy) return;
    this.closeSession.emit({ declared: Number(this.declared), reason: this.reason.trim() });
    this.dialog.set(null);
  }
}
