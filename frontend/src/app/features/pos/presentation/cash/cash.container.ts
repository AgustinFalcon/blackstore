import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { PosSessionStore } from '../../../../core/session/pos-session.store';
import { StaffRole, roleCapabilities } from '../../../../core/session/staff-role';
import { CashSessionUseCase } from '../../application/cash-session.usecase';
import { LoadCounterUseCase } from '../../application/load-counter.usecase';
import { PosStore } from '../../application/pos.store';
import { CashViewComponent, CashViewState, CloseCashRequest, ExpenseRequest, OpenCashRequest } from './cash.view';

@Component({
  selector: 'bs-cash-page',
  standalone: true,
  imports: [CashViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-cash-view [state]="state()" (retry)="reload()" (openSession)="open($event)" (closeSession)="close($event)" (addExpense)="expense($event)" />`,
})
export class CashContainerComponent implements OnInit {
  private readonly store = inject(PosStore);
  private readonly session = inject(PosSessionStore);
  private readonly counter = inject(LoadCounterUseCase);
  private readonly cash = inject(CashSessionUseCase);

  readonly state = computed<CashViewState>(() => {
    const actor = this.session.actor();
    const workspace = this.store.workspace();
    return {
      loading: this.store.counterLoading(),
      busy: this.store.cashBusy(),
      error: this.store.cashError() ?? this.store.sessionsError(),
      notice: this.store.cashNotice(),
      persistence: workspace?.persistence ?? '—',
      session: this.store.openSession(),
      terminalId: workspace?.terminalId ?? 0,
      cashierId: actor?.actorId ?? workspace?.cashierId ?? 0,
      lockCashier: actor?.role === StaffRole.Cashier,
      writesDisabled: this.store.writesDisabled(),
      canArqueo: roleCapabilities(actor?.role ?? null).close,
    };
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.counter.refresh();
  }

  open(input: OpenCashRequest): void {
    this.cash.open(input);
  }

  close(input: CloseCashRequest): void {
    this.cash.close(input.sessionId, input.declared, input.reason);
  }

  expense(input: ExpenseRequest): void {
    this.cash.addExpense({
      cashSessionId: input.sessionId,
      category: input.category,
      amount: input.amount,
      reason: input.reason,
    });
  }
}
