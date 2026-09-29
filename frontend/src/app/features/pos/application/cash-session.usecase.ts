import { Injectable, inject } from '@angular/core';
import { ApiFailure } from '../../../core/http/api-error';
import { PosPaymentMethod } from '../domain/closed-status';
import { BlackstoreApiService } from '../infrastructure/blackstore-api.service';
import { screenCopy } from './load-counter.usecase';
import { PosStore } from './pos.store';

@Injectable({ providedIn: 'root' })
export class CashSessionUseCase {
  private readonly api = inject(BlackstoreApiService);
  private readonly store = inject(PosStore);

  open(input: { readonly terminalId: number; readonly cashierId: number; readonly openingCash: number }): void {
    if (this.store.writesDisabled() || this.store.openSession()) return;
    this.store.cashBusy.set(true);
    this.store.cashError.set(null);
    this.store.cashNotice.set(null);
    this.api.openSession(input).subscribe((result) => {
      this.store.cashBusy.set(false);
      if (!result.ok) {
        this.fail(result.failure, 'No se pudo abrir la sesión');
        return;
      }
      this.store.cashNotice.set(`Sesión ${result.data.id} abierta`);
      this.reload();
    });
  }

  close(sessionId: number, declared: number, reason: string): void {
    if (this.store.writesDisabled()) return;
    this.store.cashBusy.set(true);
    this.store.cashError.set(null);
    this.api.closeSession(sessionId, { declared, reason }).subscribe((result) => {
      this.store.cashBusy.set(false);
      if (!result.ok) {
        this.fail(result.failure, 'No se pudo cerrar la sesión');
        return;
      }
      this.store.cashNotice.set(`Sesión ${result.data.id} cerrada`);
      this.reload();
    });
  }

  addExpense(input: { readonly cashSessionId: number; readonly category: string; readonly amount: number; readonly reason: string }): void {
    if (this.store.writesDisabled()) return;
    this.store.cashBusy.set(true);
    this.store.cashError.set(null);
    this.api
      .expense({ ...input, method: PosPaymentMethod.Cash })
      .subscribe((result) => {
        this.store.cashBusy.set(false);
        if (!result.ok) {
          this.fail(result.failure, 'No se pudo registrar el gasto');
          return;
        }
        this.store.rememberExpense({
          id: result.data.id,
          cashSessionId: input.cashSessionId,
          category: result.data.category,
          amount: result.data.amount,
          reason: input.reason,
        });
        this.store.cashNotice.set(`Gasto ${result.data.category} registrado`);
      });
  }

  private reload(): void {
    this.api.cashSessions().subscribe((result) => {
      if (result.ok) this.store.sessions.set(result.data);
    });
  }

  private fail(failure: ApiFailure, fallback: string): void {
    if (failure.errorCode === 'CAPABILITY_DISABLED') this.store.blockWrites();
    this.store.cashError.set(screenCopy(failure, fallback));
  }
}
