import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { formatEfectivo } from '../../domain/money';
import { CashSession } from '../../domain/pos.models';
import { PosDialogComponent } from '../dialog/pos-dialog.component';

export interface CashViewState {
  readonly loading: boolean;
  readonly busy: boolean;
  readonly error: string | null;
  readonly notice: string | null;
  readonly persistence: string;
  readonly session: CashSession | null;
  readonly terminalId: number;
  readonly cashierId: number;
  readonly lockCashier: boolean;
  readonly writesDisabled: boolean;
  readonly canArqueo: boolean;
}

export interface OpenCashRequest {
  readonly terminalId: number;
  readonly cashierId: number;
  readonly openingCash: number;
}

export interface CloseCashRequest {
  readonly sessionId: number;
  readonly declared: number;
  readonly reason: string;
}

export interface ExpenseRequest {
  readonly sessionId: number;
  readonly category: string;
  readonly amount: number;
  readonly reason: string;
}

@Component({
  selector: 'bs-cash-view',
  standalone: true,
  imports: [FormsModule, RouterLink, PosDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cash.view.html',
})
export class CashViewComponent {
  readonly state = input.required<CashViewState>();
  readonly retry = output<void>();
  readonly openSession = output<OpenCashRequest>();
  readonly closeSession = output<CloseCashRequest>();
  readonly addExpense = output<ExpenseRequest>();
  readonly format = formatEfectivo;
  readonly dialog = signal<'close' | 'expense' | null>(null);

  terminalId = 0;
  cashierId = 0;
  openingCash = 0;
  declared = 0;
  closeReason = '';
  expenseCategory = '';
  expenseAmount = 0;
  expenseReason = '';

  submitOpen(): void {
    this.openSession.emit({
      terminalId: this.resolvedTerminal(),
      cashierId: this.resolvedCashier(),
      openingCash: Number(this.openingCash),
    });
  }

  submitClose(): void {
    const session = this.state().session;
    if (!session || !this.closeReady()) return;
    this.closeSession.emit({ sessionId: session.id, declared: Number(this.declared), reason: this.closeReason.trim() });
    this.dialog.set(null);
  }

  submitExpense(): void {
    const session = this.state().session;
    if (!session || !this.expenseReady()) return;
    this.addExpense.emit({
      sessionId: session.id,
      category: this.expenseCategory.trim(),
      amount: Number(this.expenseAmount),
      reason: this.expenseReason.trim(),
    });
    this.dialog.set(null);
  }

  closeReady(): boolean {
    return this.closeReason.trim().length > 0 && Number.isFinite(Number(this.declared)) && Number(this.declared) >= 0;
  }

  expenseReady(): boolean {
    return this.expenseCategory.trim().length > 0 && this.expenseReason.trim().length > 0 && Number(this.expenseAmount) > 0;
  }

  resolvedTerminal(): number {
    return Number(this.terminalId) > 0 ? Number(this.terminalId) : this.state().terminalId;
  }

  resolvedCashier(): number {
    if (this.state().lockCashier) return this.state().cashierId;
    return Number(this.cashierId) > 0 ? Number(this.cashierId) : this.state().cashierId;
  }
}
