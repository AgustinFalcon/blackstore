import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';

interface CashSessionData {
  id: number;
  terminalId: number;
  cashierId: number;
  status: string;
  openingCash: number;
}

@Component({
  selector: 'bs-cash-session',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="page">
      <h2>Caja</h2>
      <p class="lede">Una sesión abierta por terminal. El cierre queda auditado y no edita la apertura.</p>
      <div class="card">
        <p>Persistencia: <span class="sku">{{ persistence() }}</span></p>
        @if (loading()) {
          <p class="skeleton" aria-hidden="true"></p>
          <p>Cargando puesto de trabajo…</p>
        }
        <form (ngSubmit)="open()">
          <label>Terminal <input name="terminalId" type="number" [(ngModel)]="terminalId" required /></label>
          <label>Cajero <input name="cashierId" type="number" [(ngModel)]="cashierId" required /></label>
          <label>Apertura <input name="openingCash" type="number" [(ngModel)]="openingCash" min="0" required /></label>
          <button type="submit" [disabled]="session()?.status === 'OPEN'">Abrir sesión</button>
        </form>
      </div>
      @if (session(); as opened) {
        <div class="card">
          <p>
            Sesión {{ opened.id }} en terminal {{ opened.terminalId }}
            <span class="badge" [class.ok]="opened.status === 'OPEN'" [class.info]="opened.status === 'CLOSED'">{{ opened.status }}</span>
          </p>
          @if (opened.status === 'OPEN') {
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

  terminalId = 10;
  cashierId = 7;
  openingCash = 0;
  readonly persistence = signal('memory');
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
    this.http.get<BaseResponse<{ terminalId: number; cashierId: number; persistence: string }>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (!response.data) return;
        this.terminalId = response.data.terminalId;
        this.cashierId = response.data.cashierId;
        this.persistence.set(response.data.persistence);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('No se pudo leer el puesto de trabajo');
      },
    });
    this.http.get<BaseResponse<CashSessionData[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: (response) => {
        const open = response.data?.find((item) => item.status === 'OPEN');
        if (open) this.session.set(open);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  open(): void {
    this.error.set(null);
    this.http
      .post<BaseResponse<CashSessionData>>(`${API_BASE}/cash-sessions`, {
        terminalId: Number(this.terminalId),
        cashierId: Number(this.cashierId),
        openingCash: Number(this.openingCash),
      }, {
        headers: {
          'X-Actor-Id': String(this.cashierId),
          'X-Role': 'CASHIER',
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (response.data) this.session.set(response.data);
          else this.error.set(response.errorCode ?? 'Sin datos');
        },
        error: (err: HttpErrorResponse) => {
          const body = err.error as BaseResponse<unknown> | undefined;
          this.error.set(body?.errorCode ?? body?.message ?? 'No se pudo abrir la sesión');
        },
      });
  }

  close(): void {
    const opened = this.session();
    if (!opened) return;
    this.error.set(null);
    this.http
      .post<BaseResponse<CashSessionData>>(`${API_BASE}/cash-sessions/${opened.id}/close`, {
        declared: Number(this.declared),
        reason: this.closeReason,
      }, {
        headers: {
          'X-Actor-Id': String(this.cashierId),
          'X-Role': 'CASHIER',
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (response.data) {
            this.session.set(response.data);
            this.notice.set(`Sesión ${response.data.id} cerrada`);
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
    if (!opened) return;
    this.error.set(null);
    this.http
      .post<BaseResponse<{ id: number; amount: number; category: string }>>(`${API_BASE}/expenses`, {
        cashSessionId: opened.id,
        category: this.expenseCategory,
        amount: Number(this.expenseAmount),
        reason: this.expenseReason,
        method: 'CASH',
      }, {
        headers: {
          'X-Actor-Id': String(this.cashierId),
          'X-Role': 'CASHIER',
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
