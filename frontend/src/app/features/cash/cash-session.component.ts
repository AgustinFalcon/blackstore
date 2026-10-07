import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { API_BASE } from '../../core/api';
import { CashMutationOutcome, PaymentMethod, PersistenceMode } from '../../core/domain/pos-types';
import { StaffPermission } from '../../core/domain/session-types';
import { SessionStore } from '../../core/services/session.store';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { BaseResponse, isSuccessResponse } from '../../core/models/base-response';
import { CashSessionData, CashSessionWire, WorkspaceWire } from '../../core/models/pos-models';
import { CounterContextService } from '../../core/services/counter-context.service';
import { CashSessionSelection } from '../../core/domain/cash-session-selection';

@Component({
  selector: 'bs-cash-session',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="page">
      <h2>Caja</h2>
      <p class="lede">Una sesión abierta por terminal. El cierre queda auditado y no edita la apertura.</p>
      <label>Caja visible
        <select name="selectedSession" [ngModel]="session()?.id ?? null" (ngModelChange)="selectSession($event)">
          <option [ngValue]="null">Seleccioná una caja o ingresá terminal y cajero</option>
          @for (item of selectableSessions(); track item.id) {
            <option [ngValue]="item.id">Caja {{ item.id }} · terminal {{ item.terminalId }} · cajero {{ item.cashierId }} · {{ item.status.label }}</option>
          }
        </select>
      </label>
      <div class="card">
        <p>Persistencia: <span class="sku">{{ persistence().label }}</span></p>
        @if (loading()) {
          <p class="skeleton" aria-hidden="true"></p>
          <p>Cargando puesto de trabajo…</p>
        }
        @if (identity.can(permissions.CashSessionOpen)) { <form (ngSubmit)="open()">
          <label>Terminal <input name="terminalId" type="number" [(ngModel)]="terminalId" required /></label>
          <label>Cajero <input name="cashierId" type="number" [(ngModel)]="cashierId" [readOnly]="!canAssignOther()" required /></label>
          <label>Motivo de asignación <input name="assignmentReason" [(ngModel)]="assignmentReason" /></label>
          <label>Apertura <input name="openingCash" type="number" [(ngModel)]="openingCash" min="0" required /></label>
          <button type="submit" [disabled]="!canOpen()">Abrir sesión</button>
        </form> }
      </div>
      @if (session(); as opened) {
        <div class="card">
          <p>
            Sesión {{ opened.id }} en terminal {{ opened.terminalId }}
            <span class="badge" [class.ok]="opened.status.isOpen" [class.info]="opened.status.isClosed">{{ opened.status.label }}</span>
          </p>
          @if (selection().state.permitsMutation && identity.can(permissions.CashSessionClose)) {
            <form (ngSubmit)="close()">
              <label>Declarado <input name="declared" type="number" [(ngModel)]="declared" min="0" required /></label>
              <label>Motivo de cierre <input name="closeReason" [(ngModel)]="closeReason" required /></label>
              <button type="submit" [disabled]="loading() || mutationPending() || !!error()">Cerrar sesión</button>
            </form>
            <form (ngSubmit)="addExpense()">
              <label>Categoría <input name="category" [(ngModel)]="expenseCategory" required /></label>
              <label>Gasto <input name="expenseAmount" type="number" [(ngModel)]="expenseAmount" min="0.01" required /></label>
              <label>Motivo <input name="reason" [(ngModel)]="expenseReason" required /></label>
              <button type="submit" [disabled]="loading() || mutationPending() || !!error()">Registrar gasto</button>
            </form>
          }
        </div>
      } @else if (!loading() && !error()) {
        <p class="empty">{{ selection().state.label }}</p>
      }
      @if (notice()) {
        <p class="badge" [class.ok]="outcome().isApplied" [class.info]="!outcome().isApplied" role="status">{{ notice() }}</p>
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
  readonly identity = inject(SessionStore);
  readonly permissions = StaffPermission;

  private readonly requestedTerminal = signal(10);
  private readonly requestedCashier = signal<number | null>(this.identity.staff()?.id ?? null);
  private initializedWorkstation = false;
  get terminalId(): number { return this.requestedTerminal(); }
  set terminalId(value: number) { this.initializedWorkstation = true; this.requestedTerminal.set(value); }
  get cashierId(): number | null { return this.requestedCashier(); }
  set cashierId(value: number | null) { this.requestedCashier.set(value); }
  assignmentReason = '';
  openingCash = 0;
  readonly persistence = signal(PersistenceMode.Unknown);
  readonly loading = signal(true);
  expenseCategory = 'insumos';
  expenseAmount = 2;
  expenseReason = 'bolsas';
  declared = 0;
  closeReason = 'cierre de turno';
  readonly visibleSessions = signal<readonly CashSessionData[]>([]);
  readonly selectableSessions = computed(() => CashSessionSelection.visible(this.visibleSessions(), this.identity.staff()).filter(item => !item.status.canStartNewSession));
  readonly selection = computed(() => CashSessionSelection.requested(this.visibleSessions(), this.identity.staff(), this.terminalId, this.cashierId));
  readonly session = computed(() => this.selection().session);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly outcome = signal(CashMutationOutcome.Applied);
  readonly mutationPending = signal(false);
  private reloadEpoch = 0;

  constructor() {
    this.identity.changed.pipe(takeUntilDestroyed()).subscribe(() => {
      this.reloadEpoch++; this.loading.set(false); this.mutationPending.set(false);
      this.outcome.set(CashMutationOutcome.Unknown);
      this.visibleSessions.set([]); this.cashierId = null; this.notice.set(null); this.error.set(null);
    });
    this.reload();
  }

  reload(): void {
    const generation = this.identity.generation();
    const epoch = ++this.reloadEpoch;
    const current = () => generation === this.identity.generation() && epoch === this.reloadEpoch;
    this.error.set(null);
    this.loading.set(true);
    this.counter.load();
    let pending = this.identity.can(StaffPermission.WorkspaceRead) ? 2 : 1;
    const finish = () => { if (current() && --pending === 0) this.loading.set(false); };
    if (this.identity.can(StaffPermission.WorkspaceRead)) this.http.get<BaseResponse<WorkspaceWire>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (!current()) return;
        if (isSuccessResponse(response)) {
          const workspace = PosWireMapper.workspace(response.data);
          if (!this.initializedWorkstation) { this.requestedTerminal.set(workspace.terminalId); this.initializedWorkstation = true; }
          this.persistence.set(workspace.persistence);
        } else this.error.set('No se pudo leer el puesto de trabajo');
        finish();
      },
      error: () => {
        if (!current()) return;
        this.error.set('No se pudo leer el puesto de trabajo');
        finish();
      },
    });
    this.http.get<BaseResponse<CashSessionWire[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: (response) => {
        if (!current()) return;
        if (isSuccessResponse(response)) this.visibleSessions.set(PosWireMapper.cashSessions(response.data));
        else { this.visibleSessions.set([]); this.error.set('No se pudieron leer las cajas visibles'); }
        finish();
      },
      error: () => { if (!current()) return; this.visibleSessions.set([]); this.error.set('No se pudieron leer las cajas visibles'); finish(); },
    });
  }

  open(): void {
    if (!this.canOpen()) return;
    if (this.cashierId !== this.identity.staff()?.id && (!this.canAssignOther() || !this.assignmentReason.trim())) return;
    this.error.set(null);
    const generation = this.beginMutation();
    this.http
      .post<BaseResponse<CashSessionWire>>(`${API_BASE}/cash-sessions`, {
        terminalId: Number(this.terminalId),
        cashierId: Number(this.cashierId),
        openingCash: Number(this.openingCash),
        reason: this.assignmentReason,
      }, {
        headers: {
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (!this.completeMutation(response, 200, generation)) return;
          const session = PosWireMapper.cashMutationSession(response);
          if (session) {
            this.recordSession(session);
            this.counter.load();
          } else this.rejectMutation(CashMutationOutcome.Unknown);
        },
        error: (err: HttpErrorResponse) => {
          this.completeMutation(err.error, err.status, generation);
        },
      });
  }

  close(): void {
    if (!this.identity.can(StaffPermission.CashSessionClose)) return;
    const opened = this.session();
    if (!opened || !this.selection().state.permitsMutation || this.loading() || this.mutationPending() || this.error() || !this.closeReason.trim()) return;
    this.error.set(null);
    const generation = this.beginMutation();
    this.http
      .post<BaseResponse<CashSessionWire>>(`${API_BASE}/cash-sessions/${opened.id}/close`, {
        declared: Number(this.declared),
        reason: this.closeReason,
      }, {
        headers: {
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (!this.completeMutation(response, 200, generation)) return;
          const session = PosWireMapper.cashMutationSession(response);
          if (session) {
            this.recordSession(session);
            this.notice.set(`Sesión ${session.id} ${session.status.label}`);
            this.counter.load();
          } else this.rejectMutation(CashMutationOutcome.Unknown);
        },
        error: (err: HttpErrorResponse) => {
          this.completeMutation(err.error, err.status, generation);
        },
      });
  }

  addExpense(): void {
    if (!this.identity.can(StaffPermission.ExpenseRecord)) return;
    const opened = this.session();
    if (!opened || !this.selection().state.permitsMutation || this.loading() || this.mutationPending() || this.error() || !this.expenseReason.trim()) return;
    this.error.set(null);
    const generation = this.beginMutation();
    this.http
      .post<BaseResponse<{ id: number; amount: number; category: string }>>(`${API_BASE}/expenses`, {
        cashSessionId: opened.id,
        category: this.expenseCategory,
        amount: Number(this.expenseAmount),
        reason: this.expenseReason,
        method: PaymentMethod.Cash.wire,
      }, {
        headers: {
          'X-Trace-Id': crypto.randomUUID(),
        },
      })
      .subscribe({
        next: (response) => {
          if (!this.completeMutation(response, 200, generation)) return;
          if (PosWireMapper.cashMutationExpense(response)) this.notice.set('Gasto registrado');
          else this.rejectMutation(CashMutationOutcome.Unknown);
        },
        error: (err: HttpErrorResponse) => {
          this.completeMutation(err.error, err.status, generation);
        },
      });
  }
  canAssignOther(): boolean { return this.identity.staff()?.role.canAssignCashier ?? false; }

  canOpen(): boolean {
    return !this.loading() && !this.mutationPending() && !this.error() && CashSessionSelection.canOpen(this.visibleSessions(), this.identity.staff(), this.terminalId, this.cashierId)
      && (this.cashierId === this.identity.staff()?.id || !!this.assignmentReason.trim());
  }

  selectSession(id: number | null): void {
    const matches = this.selectableSessions().filter(item => item.id === id);
    if (matches.length !== 1) { this.cashierId = null; return; }
    this.terminalId = matches[0].terminalId;
    this.cashierId = matches[0].cashierId;
  }

  private recordSession(session: CashSessionData): void {
    this.visibleSessions.update(sessions => [...sessions.filter(item => item.id !== session.id), session]);
  }

  private beginMutation(): number {
    this.mutationPending.set(true); this.notice.set(null);
    return this.identity.generation();
  }

  private completeMutation(response: unknown, status: number, generation: number): boolean {
    if (generation !== this.identity.generation()) return false;
    this.mutationPending.set(false);
    const outcome = PosWireMapper.cashMutationOutcome(response, status);
    this.outcome.set(outcome);
    if (outcome === CashMutationOutcome.Applied) return true;
    this.rejectMutation(outcome);
    return false;
  }

  private rejectMutation(outcome: CashMutationOutcome): void {
    this.outcome.set(outcome); this.notice.set(null);
    if (outcome.clearsSelection || outcome.reloadsContext) {
      this.visibleSessions.set([]);
      this.counter.invalidate();
    }
    if (outcome.clearsSelection) {
      this.reloadEpoch++; this.loading.set(false); this.cashierId = null;
    }
    if (outcome.reloadsContext) {
      this.notice.set(outcome.label);
      this.reload();
    } else this.error.set(outcome.label);
  }
}
