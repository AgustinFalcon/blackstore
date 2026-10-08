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
import { AccountingCommand, AccountingCommandKind, CommandOutcome, CommandReceipt, ExpenseOperation } from '../../core/domain/accounting-command';
import { AccountingCommandsStore } from '../../core/services/accounting-commands.store';
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
              <button type="submit" [disabled]="!canMutate()">Cerrar sesión</button>
            </form>
          }
          @if (selection().state.permitsMutation && identity.can(permissions.ExpenseRecord)) {
            <form (ngSubmit)="addExpense()">
              <label>Categoría <input name="category" [(ngModel)]="expenseCategory" required /></label>
              <label>Gasto <input name="expenseAmount" type="number" [(ngModel)]="expenseAmount" min="0.01" required /></label>
              <label>Motivo <input name="reason" [(ngModel)]="expenseReason" required /></label>
              <button type="submit" [disabled]="!canMutate()">Registrar gasto</button>
            </form>
          }
        </div>
      } @else if (!loading() && !error()) {
        <p class="empty">{{ selection().state.label }}</p>
      }
      @if (notice()) {
        <p class="badge" [class.ok]="outcome().isApplied" [class.info]="!outcome().isApplied" role="status">{{ notice() }}</p>
      }
      @if (commands.unresolved(); as command) {
        <p>Comando {{ command.commandId }} pendiente de comprobación.</p>
        <button type="button" (click)="consultReceipt()" [disabled]="mutationPending()">Consultar recibo</button>
      }
      <p role="status">{{ commands.runtime.notice() }}</p>
      <button type="button" (click)="commands.runtime.refresh()" [disabled]="mutationPending()">Comprobar contexto y evidencia</button>
      @if (commands.receipt()?.closeSnapshot; as snapshot) {
        <p>Arqueo de caja {{ commands.receipt()?.cashSessionId }}: {{ snapshot.outcome.label }} · {{ snapshot.coverage.label }}.
        Esperado {{ snapshot.expected?.decimal ?? 'no disponible' }} · declarado {{ snapshot.declared.decimal }} · diferencia {{ snapshot.difference?.decimal ?? 'no disponible' }}</p>
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
  readonly commands = inject(AccountingCommandsStore);

  private readonly requestedTerminal = signal(0);
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
    this.mutate(AccountingCommand.create(AccountingCommandKind.Open, {
      terminalId: Number(this.terminalId), cashierId: Number(this.cashierId), openingCash: Number(this.openingCash), reason: this.assignmentReason,
    }));
  }
  close(): void {
    const opened = this.session();
    if (!this.identity.can(StaffPermission.CashSessionClose) || !opened || !this.selection().state.permitsMutation || !this.canMutate() || !this.closeReason.trim()) return;
    this.mutate(AccountingCommand.create(AccountingCommandKind.Close, {
      cashSessionId: opened.id, declaredCash: Number(this.declared), reason: this.closeReason,
    }, opened.id));
  }
  addExpense(): void {
    const opened = this.session();
    if (!this.identity.can(StaffPermission.ExpenseRecord) || !opened || !this.selection().state.permitsMutation || !this.canMutate() || !this.expenseReason.trim()) return;
    this.mutate(AccountingCommand.create(AccountingCommandKind.Expense, {
      cashSessionId: opened.id, category: this.expenseCategory, amount: Number(this.expenseAmount), reason: this.expenseReason,
      operation: ExpenseOperation.AccrueAndSettle.wire, paymentMethod: PaymentMethod.Cash.wire,
    }));
  }
  canMutate(): boolean {
    return !this.loading() && !this.mutationPending() && !this.error() && !this.commands.unresolved() && !this.commands.blocked().blocksWrites;
  }
  consultReceipt(): void {
    if (this.mutationPending()) return;
    this.mutationPending.set(true);
    this.commands.consult().subscribe(receipt => this.recordReceipt(receipt));
  }
  private mutate(command: AccountingCommand): void {
    this.mutationPending.set(true); this.notice.set(null);
    this.commands.execute(command).subscribe(receipt => this.recordReceipt(receipt));
  }
  private recordReceipt(receipt: CommandReceipt): void {
    this.mutationPending.set(false);
    this.notice.set(receipt.failure.label || receipt.outcome.label);
    if (receipt.outcome === CommandOutcome.Committed) { this.outcome.set(CashMutationOutcome.Applied); this.reload(); }
    else { this.outcome.set(CashMutationOutcome.Unknown); this.error.set(receipt.failure.label || receipt.outcome.label); }
  }
  canAssignOther(): boolean { return this.identity.staff()?.role.canAssignCashier ?? false; }

  canOpen(): boolean {
    return this.canMutate() && CashSessionSelection.canOpen(this.visibleSessions(), this.identity.staff(), this.terminalId, this.cashierId)
      && (this.cashierId === this.identity.staff()?.id || !!this.assignmentReason.trim());
  }

  selectSession(id: number | null): void {
    const matches = this.selectableSessions().filter(item => item.id === id);
    if (matches.length !== 1) { this.cashierId = null; return; }
    this.terminalId = matches[0].terminalId;
    this.cashierId = matches[0].cashierId;
  }

}
