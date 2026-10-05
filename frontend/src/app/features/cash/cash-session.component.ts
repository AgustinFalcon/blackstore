import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { API_BASE } from '../../core/api';
import { PaymentMethod, PersistenceMode, StaffRole } from '../../core/domain/pos-types';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { BaseResponse } from '../../core/models/base-response';
import { CashSessionData, CashSessionWire, WorkspaceWire } from '../../core/models/pos-models';
import { CounterContextService } from '../../core/services/counter-context.service';

@Component({
  selector: 'bs-cash-session',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="page">
      <h2>Caja</h2>
      <p class="lede">Una sesión abierta por terminal. El cierre queda auditado y no edita la apertura.</p>
      <div class="card">
        <p>Persistencia: <span class="sku">{{ persistence().label }}</span></p>
        @if (loading()) {
          <p class="skeleton" aria-hidden="true"></p>
          <p>Cargando puesto de trabajo…</p>
        }
        <form (ngSubmit)="open()">
          <label>Terminal <input name="terminalId" type="number" [(ngModel)]="terminalId" required /></label>
          <label>Cajero <input name="cashierId" type="number" [(ngModel)]="cashierId" required /></label>
          <label>Apertura <input name="openingCash" type="number" [(ngModel)]="openingCash" min="0" required /></label>
          <button type="submit" [disabled]="session() !== null && !session()?.status?.canStartNewSession">Abrir sesión</button>
        </form>
      </div>
      @if (session(); as opened) {
        <div class="card">
          <p>
            Sesión {{ opened.id }} en terminal {{ opened.terminalId }}
            <span class="badge" [class.ok]="opened.status.isOpen" [class.info]="opened.status.isClosed">{{ opened.status.label }}</span>
          </p>
          @if (opened.status.isOpen) {
            <form (ngSubmit)="close()">
              <label>Declarado <input name="declared" type="number" [(ngModel)]="declared" min="0" required /></label>
              <label>Motivo de cierre <input name="closeReason" [(ngModel)]="closeReason" required /></label>
              <button type="submit">Cerrar sesión</button>
            </form>
            <form (ngSubmit)="addExpense()">
              <label>Categoría <input name="category" [(ngModel)]="expenseCategory" required /></label>
              <label>Gasto <input name="expenseAmount" type="number" [(ngModel)]="expenseAmount" min="0.01" required /></label>
              <label>Motivo <input name="reason" [(ngModel)]="expenseReason" required /></label>
              <button type="submit">Registrar gasto</button>
            </form>
          }
        </div>
      } @else if (!loading() && !error()) {
        <p class="empty">No hay sesión abierta en este terminal.</p>
      }
      @if (notice()) {
        <p class="badge ok" role="status">{{ notice() }}</p>
      }
      @if (error()) {
        <div class="retry-row">
          <p class="error">{{ error() }}</p>
          <button type="button" class="ghost" (click)="reload()">Reintentar</button>
        </div>
      }
    </section>
  `,
})
export class CashSessionComponent {
  private readonly http = inject(HttpClient);
  private readonly counter = inject(CounterContextService);

  terminalId = 10;
  cashierId = 7;
  openingCash = 0;
  readonly persistence = signal(PersistenceMode.Unknown);
  readonly loading = signal(true);
  expenseCategory = 'insumos';
  expenseAmount = 2;
  expenseReason = 'bolsas';
  declared = 0;
  closeReason = 'cierre de turno';
  readonly session = signal<CashSessionData | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);

  constructor() {
    this.reload();
  }

  reload(): void {
    this.error.set(null);
    this.loading.set(true);
    this.counter.load();
    this.http.get<BaseResponse<WorkspaceWire>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (!response.data) return;
        const workspace = PosWireMapper.workspace(response.data);
        this.terminalId = workspace.terminalId;
        this.cashierId = workspace.cashierId;
        this.persistence.set(workspace.persistence);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('No se pudo leer el puesto de trabajo');
      },
    });
    this.http.get<BaseResponse<CashSessionWire[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: (response) => {
        const sessions = PosWireMapper.cashSessions(response.data);
        const session = sessions.find((item) => item.status.isOpen)
          ?? sessions.find((item) => !item.status.canStartNewSession);
        this.session.set(session ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  open(): void {
    const current = this.session();
    if (current && !current.status.canStartNewSession) return;
    this.error.set(null);
    this.http
      .post<BaseResponse<CashSessionWire>>(`${API_BASE}/cash-sessions`, {
        terminalId: Number(this.terminalId),
        cashierId: Number(this.cashierId),
        openingCash: Number(this.openingCash),
      }, {
        headers: {
          'X-Actor-Id': String(this.cashierId),
          'X-Role': StaffRole.Cashier.wire,
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (response.data) {
            this.session.set(PosWireMapper.cashSession(response.data));
            this.counter.load();
          } else this.error.set(response.errorCode ?? 'Sin datos');
        },
        error: (err: HttpErrorResponse) => {
          const body = err.error as BaseResponse<unknown> | undefined;
          this.error.set(body?.errorCode ?? body?.message ?? 'No se pudo abrir la sesión');
        },
      });
  }

  close(): void {
    const opened = this.session();
    if (!opened?.status.isOpen) return;
    this.error.set(null);
    this.http
      .post<BaseResponse<CashSessionWire>>(`${API_BASE}/cash-sessions/${opened.id}/close`, {
        declared: Number(this.declared),
        reason: this.closeReason,
      }, {
        headers: {
          'X-Actor-Id': String(this.cashierId),
          'X-Role': StaffRole.Cashier.wire,
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (response.data) {
            const session = PosWireMapper.cashSession(response.data);
            this.session.set(session);
            this.notice.set(`Sesión ${session.id} ${session.status.label}`);
            this.counter.load();
          }
        },
        error: (err: HttpErrorResponse) => {
          const body = err.error as BaseResponse<unknown> | undefined;
          this.error.set(body?.errorCode ?? body?.message ?? 'No se pudo cerrar la sesión');
        },
      });
  }

  addExpense(): void {
    const opened = this.session();
    if (!opened?.status.isOpen) return;
    this.error.set(null);
    this.http
      .post<BaseResponse<{ id: number; amount: number; category: string }>>(`${API_BASE}/expenses`, {
        cashSessionId: opened.id,
        category: this.expenseCategory,
        amount: Number(this.expenseAmount),
        reason: this.expenseReason,
        method: PaymentMethod.Cash.wire,
      }, {
        headers: {
          'X-Actor-Id': String(this.cashierId),
          'X-Role': StaffRole.Cashier.wire,
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          this.notice.set(response.data ? `Gasto ${response.data.category} registrado` : 'Gasto registrado');
        },
        error: (err: HttpErrorResponse) => {
          const body = err.error as BaseResponse<unknown> | undefined;
          this.error.set(body?.errorCode ?? body?.message ?? 'No se pudo registrar el gasto');
        },
      });
  }
}
