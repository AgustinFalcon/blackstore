import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { CashSessionUseCase } from '../../application/cash-session.usecase';
import { LoadCounterUseCase } from '../../application/load-counter.usecase';
import { PosStore } from '../../application/pos.store';
import { PaymentStatus } from '../../domain/closed-status';
import { cashCollectedForSession, expectedDrawer } from '../../domain/sale-block';
import { CashCloseViewComponent, CashCloseViewState, DrawerLine } from './cash-close.view';

@Component({
  selector: 'bs-cash-close-page',
  standalone: true,
  imports: [CashCloseViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-cash-close-view [state]="state()" (retry)="reload()" (closeSession)="close($event)" />`,
})
export class CashCloseContainerComponent implements OnInit {
  private readonly store = inject(PosStore);
  private readonly counter = inject(LoadCounterUseCase);
  private readonly cash = inject(CashSessionUseCase);

  readonly state = computed<CashCloseViewState>(() => {
    const open = this.store.openSession();
    const latestClosed = [...this.store.sessions()].reverse().find((session) => session.status.closed) ?? null;
    const session = open ?? latestClosed;
    const expenses = session ? this.store.expenseLog().filter((expense) => expense.cashSessionId === session.id) : [];
    const sales = Object.values(this.store.salesById()).filter((sale) => session !== null && sale.cashSessionId === session.id);
    const cashIn = session ? cashCollectedForSession(session.id, sales) : 0;
    const expenseTotal = expenses.reduce((sum, expense) => sum + expense.amount, 0);
    const movements: DrawerLine[] = [
      ...sales.flatMap((sale) =>
        sale.payments
          .filter((payment) => payment.method.cash)
          .map((payment) => ({
            label: payment.status === PaymentStatus.Refunded ? `Reversa ${payment.id}` : `Cobro ${payment.id}`,
            amount: payment.status.collected ? payment.amount : -payment.amount,
          })),
      ),
      ...expenses.map((expense) => ({ label: `Gasto ${expense.category}`, amount: -expense.amount })),
    ];
    return {
      loading: this.store.counterLoading(),
      busy: this.store.cashBusy(),
      error: this.store.cashError() ?? this.store.sessionsError(),
      notice: this.store.cashNotice(),
      session,
      expected: session ? expectedDrawer({ openingCash: session.openingCash, expenses, cashIn }) : 0,
      cashIn,
      expenseTotal,
      movements,
      closed: session?.status.closed ?? false,
      writesDisabled: this.store.writesDisabled(),
    };
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.counter.refresh();
  }

  close(input: { readonly declared: number; readonly reason: string }): void {
    const session = this.store.openSession();
    if (!session) return;
    this.cash.close(session.id, input.declared, input.reason);
  }
}
