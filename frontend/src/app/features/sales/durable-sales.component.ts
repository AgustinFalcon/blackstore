import { Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AllowedAction, DurableSaleState } from '../../core/domain/durable-sale';
import { PaymentMethod, PaymentStatus } from '../../core/domain/pos-types';
import { DurableSalesStore } from '../../core/services/durable-sales.store';
import { ConfirmationPhase } from '../../core/domain/confirmation-phase';
import { ReversalReason } from '../../core/domain/reversal-reason';
import { ConfirmationDialogComponent } from '../../shared/confirmation-dialog.component';
import { SaleRouteId } from '../../core/domain/sale-snapshot-state';
import { SurfaceDataSource } from '../../core/domain/data-provenance';
import { SalePresentation } from './sale-presentation';
import { SurfaceDataEvidenceComponent } from '../../shared/surface-data-evidence.component';
import { OperationId, SaleOperationSelection, SaleSelectionPhase } from '../../core/domain/sale-operation-selection';
import { TicketMoney } from '../../core/domain/ticket-transition';

interface DurableReversalReview {
  readonly operationId: string;
  readonly paymentId: number;
  readonly reason: ReversalReason;
}

interface DurableReleaseReview {
  readonly operationId: string;
  readonly reason: string;
}

@Component({
  selector: 'bs-durable-sales', standalone: true, imports: [FormsModule, RouterLink, ConfirmationDialogComponent, SurfaceDataEvidenceComponent],
  template: `
    <section class="page">
      <div class="section-heading"><div><p class="eyebrow">Actividad comercial</p><h2 id="sales-list-heading" tabindex="-1">Ventas existentes</h2>
      <p class="lede">Consultá tus ventas, revisá los cobros y continuá las operaciones pendientes.</p></div>
      <a class="primary" routerLink="/ticket">Iniciar nueva venta</a></div>
      <bs-surface-data-evidence surfaceId="BS-S08" [source]="surfaceSource()" [datasets]="surfaceDatasets()" />
      <div class="card" aria-label="Procedencia de las ventas">
        <p><strong>Registro BlackStore:</strong> <span class="badge" [attr.data-source]="store.listSource().wire">{{ store.listSource().label }}</span></p>
        <p><strong>Origen histórico del producto:</strong> <span class="badge warn" [attr.data-source]="historicalSource.wire">{{ historicalSource.label }}</span>. La operación conserva sus importes; su origen de catálogo no fue registrado.</p>
      </div>
      <div class="card">
        <label>Estado <select [(ngModel)]="filter" (ngModelChange)="store.list(null, filter)">
          <option [ngValue]="null">Todos</option>
          @for (state of states; track state.wire) { <option [ngValue]="state">{{ state.label }}</option> }
        </select></label>
        <button type="button" (click)="store.list(null, filter)" [disabled]="store.loadingList()">Reintentar consulta</button>
        @if (store.loadingList()) { <p role="status">Consultando ventas…</p> }
        @else if (store.listSource() === sources.Unavailable) {
          <p class="banner warn" role="alert">No pudimos comprobar el listado. Reintentá la consulta.</p>
        } @else {
          @for (sale of store.items(); track sale.identity.operationId) {
            <article class="sale-row">
              <div class="section-heading"><h3>{{ sale.status.label }}</h3><strong class="money">{{ formatAmount(sale.total) }}</strong></div>
              <p>Caja {{ sale.cashSessionId }} · Creada: {{ formatInstant(sale.createdAt) }}</p>
              <details><summary>Identidad de la venta</summary><p class="sku">{{ sale.identity.operationId }}</p></details>
              <div class="row-actions">
                <button type="button" [id]="operationTriggerId(sale.identity.operationId)" (click)="selectOperation(sale.identity.operationId)"
                  [disabled]="store.busy()" [attr.aria-pressed]="isOperationSelected(sale.identity.operationId)">
                  Ver operaciones
                </button>
                <a routerLink="/ticket" [queryParams]="{operationId:sale.identity.operationId}">Continuar en punto de venta</a>
                <a [routerLink]="['/ticket', ticketPathSegment(sale.identity.saleId)]">Ver ticket</a>
              </div>
            </article>
          } @empty { <p>No hay ventas visibles en esta página.</p> }
          @if (store.nextCursor(); as cursor) { <button type="button" (click)="store.list(cursor, filter)">Página siguiente</button> }
        }
      </div>
      @if (store.notice()) { <p class="banner warn" role="alert">{{ store.notice() }}</p> }
      <p role="status">{{ store.commands.runtime.notice() }}</p>
      <button type="button" (click)="store.commands.runtime.refresh()" [disabled]="store.busy()">Comprobar contexto y evidencia</button>
      @if (store.sales.unresolved()) {
        <button type="button" (click)="store.consultReceipt()" [disabled]="store.busy()">Consultar admisión pendiente</button>
      }
      @if (store.commands.unresolved(); as command) {
        <p>Comando {{ command.commandId }} pendiente de comprobación.</p>
        <button type="button" (click)="store.consultReceipt()" [disabled]="store.busy()">Consultar recibo</button>
      }
      @if (store.busy()) { <p role="status">Comprobando operación…</p> }
      @if (store.selectionPhase().showsPanel) {
        <section class="card operation-panel" role="region" aria-labelledby="selected-operation-heading">
          <h3 id="selected-operation-heading" tabindex="-1">Operaciones de la venta</h3>
          @if (store.selectedOperationId(); as selected) { <p class="sku">Operación seleccionada: {{ selected.wire }}</p> }
          @if (store.selectionPhase() === phases.Loading) {
            <p role="status">{{ phases.Loading.label }}</p>
            <button type="button" (click)="closeSelection()">Cerrar</button>
          } @else if (store.selectionPhase() === phases.Unknown) {
            <p class="banner warn" role="alert">{{ phases.Unknown.label }}</p>
            <button type="button" (click)="closeSelection()">Cerrar</button>
          } @else if (store.selectionPhase() === phases.Unavailable) {
            <p class="banner warn" role="alert">{{ phases.Unavailable.label }} No mostramos datos anteriores.</p>
            <div class="row-actions">
              <button type="button" (click)="store.retrySelection()">Reintentar venta</button>
              <button type="button" (click)="closeSelection()">Cerrar</button>
            </div>
          } @else if (store.detail(); as sale) {
          <p class="selection-status" role="status">{{ phases.Ready.label }}</p>
          <div class="section-heading"><h4>{{ sale.status.label }}</h4><span class="badge">{{ sale.coverage.label }}</span></div>
          <p><strong>Lectura de la operación:</strong> <span class="badge" [attr.data-source]="store.detailSource().wire">{{ store.detailSource().label }}</span></p>
          <dl class="metrics"><div class="metric"><dt>Total</dt><dd>{{ formatAmount(sale.total) }}</dd></div><div class="metric"><dt>Saldo pendiente</dt><dd>{{ formatAmount(sale.pending) }}</dd></div><div class="metric"><dt>Creada</dt><dd>{{ formatInstant(sale.createdAt) }}</dd></div><div class="metric"><dt>Última actualización</dt><dd>{{ formatInstant(sale.updatedAt) }}</dd></div></dl>
          <h4>Artículos</h4>
          @for (line of sale.lines; track $index) { <p><strong>{{ line.quantity }} × {{ line.productName }}</strong> · {{ formatAmount(line.total) }} <small class="sku">{{ line.sku }}</small></p> }
          @empty { <p>No hay artículos disponibles en esta lectura.</p> }
          <h4>Pagos registrados</h4>
          @if (sale.pendingCommand; as command) { <p>{{ command.label }}. Consultá el estado; el recovery durable lo administra el backend.</p> }
          @for (payment of sale.payments; track payment.paymentId) {
            <p><strong>{{ payment.method.label }} · {{ formatAmount(payment.amount) }}</strong> · {{ payment.status.label }} · comisión {{ formatAmount(payment.fee) }} · Registrado {{ formatInstant(payment.createdAt) }}</p>
            <p>{{ payment.reversibility.label }}. Tras una devolución, las nuevas reversas requieren evidencia por pago del backend.</p>
            @if (payment.reversibility.permitsReverse && store.can(actions.ReversePayment)) {
              <button type="button" (click)="requestReverse(payment.paymentId)" [disabled]="!validReversalReason()">Reversar pago {{ payment.paymentId }}</button>
            }
          } @empty { <p>No hay pagos registrados.</p> }
          <details><summary>Evidencia técnica de la operación</summary>
            <p>Caja {{ sale.cashSessionId }} · responsable de caja {{ sale.cashierId }} · actor histórico {{ sale.createdBy ?? 'No disponible' }}</p>
            <p>Cliente técnico <span class="sku">{{ sale.identity.clientInstanceId }}</span> · dispositivo {{ sale.identity.deviceId }}</p>
            <p>Venta <span class="sku">{{ sale.identity.saleId }}</span> · operación {{ sale.identity.operationId }}</p>
            <p>Recibo {{ sale.receipt ?? 'No disponible' }} · reserva {{ sale.reservationRef ?? 'No disponible' }}</p>
          </details>
          @if (!sale.status.acceptsCommands || !sale.valid) { <p role="status">Sólo consulta. Reintentá consultar o solicitá reconciliación a una persona autorizada.</p> }
          <label>Motivo de operación <input [(ngModel)]="reason" aria-describedby="durable-reversal-reason-guidance" /></label>
          <p id="durable-reversal-reason-guidance">Para reversar un pago: {{ reversalReasonGuidance }}</p>
          @if (store.can(actions.CapturePayment)) {
            <label>Importe a completar <input type="number" min="0.01" step="0.01" [(ngModel)]="amount" aria-describedby="capture-amount-guidance" /></label>
            @if (captureValidationMessage(); as validation) { <p id="capture-amount-guidance" class="banner warn">{{ validation }}</p> }
            @else { <p id="capture-amount-guidance">El importe no puede superar el saldo pendiente.</p> }
            <p>Las comisiones pagadas se registran por el circuito operativo del titular.</p>
            <label>Medio <select [(ngModel)]="method">@for (option of methods; track option.wire) { <option [ngValue]="option">{{ option.label }}</option> }</select></label>
            <button type="button" (click)="capturePayment()" [disabled]="captureValidationMessage() !== null">Completar pago</button>
          }
          @if (store.can(actions.Commit)) { <button type="button" (click)="store.execute(actions.Commit, reason)">Confirmar venta</button> }
          @if (store.can(actions.Release)) { <button type="button" (click)="requestRelease()">Liberar reserva</button> }
          <div class="row-actions">
            <button type="button" (click)="store.refreshSelection()">Consultar estado</button>
            <a routerLink="/ticket" [queryParams]="{operationId:sale.identity.operationId}">Continuar operación en punto de venta</a>
            <button type="button" (click)="closeSelection()">Cerrar detalle</button>
          </div>
          }
        </section>
      }
      <bs-confirmation-dialog [phase]="reversalPhase()" title="Confirmar reversa" titleId="sale-reversal-title"
        [description]="reversalDescription()" [detail]="reversalReview() ? 'Motivo: ' + reversalReview()!.reason.wire : ''"
        confirmLabel="Confirmar reversa" cancelLabel="Cancelar"
        (confirmed)="confirmReverse()" (cancelled)="cancelReverse()" />
      <bs-confirmation-dialog [phase]="releasePhase()" title="Liberar reserva" titleId="existing-sale-release-title"
        description="El stock reservado volverá a quedar disponible. El historial de pagos no se elimina."
        [detail]="releaseReview() ? 'Operación: ' + releaseReview()!.operationId : ''"
        confirmLabel="Liberar reserva" cancelLabel="Mantener reserva"
        (confirmed)="confirmRelease()" (cancelled)="cancelRelease()" />
    </section>
  `,
  styles: [`
    .sale-row { padding: 1rem 0; border-top: 1px solid var(--bs-line); }
    .sale-row a { display: inline-flex; }
    .sale-row h3 { margin: 0; }
    .row-actions { display: flex; flex-wrap: wrap; gap: .65rem 1rem; align-items: center; }
    .row-actions > * { min-height: 44px; align-items: center; }
    .operation-panel { scroll-margin-top: 1rem; }
    .operation-panel > h3 { overflow-wrap: anywhere; }
    .operation-panel button, .operation-panel a { min-height: 44px; display: inline-flex; align-items: center; }
    .selection-status { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
    details { margin: .75rem 0; }
    details .sku { overflow-wrap: anywhere; }
    @media (max-width: 480px) { .section-heading { flex-direction: column; } }
  `],
})
export class DurableSalesComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  private previousSelection = SaleOperationSelection.None;
  private returnFocusOperation: string | null = null;
  readonly historicalSource = SurfaceDataSource.Unknown;
  surfaceSource(): SurfaceDataSource { return SurfaceDataSource.aggregate([this.store.listSource(), this.historicalSource]); }
  surfaceDatasets() { return [
    {id:'sale-list', label:'Listado de ventas', source:this.store.listSource()},
    {id:'historical-product-provenance', label:'Origen histórico del producto', source:this.historicalSource},
  ] as const; }
  readonly formatAmount = SalePresentation.amount.bind(SalePresentation);
  readonly formatInstant = SalePresentation.instant.bind(SalePresentation);
  ticketPathSegment(raw: string): string { return SaleRouteId.fromWire(raw)?.pathSegment ?? 'id:'; }
  readonly store = inject(DurableSalesStore);
  readonly actions = AllowedAction;
  readonly phases = SaleSelectionPhase;
  readonly sources = SurfaceDataSource;
  readonly captured = PaymentStatus.Captured;
  readonly states = DurableSaleState.values;
  readonly methods = PaymentMethod.selectable;
  filter: DurableSaleState | null = null;
  reason = '';
  amount: string | number = '';
  method = PaymentMethod.Cash;
  readonly reversalPhase = signal(ConfirmationPhase.Idle);
  readonly releasePhase = signal(ConfirmationPhase.Idle);
  readonly reversalReview = signal<DurableReversalReview | null>(null);
  readonly reversalReasonGuidance = ReversalReason.guidance;
  readonly releaseReview = signal<DurableReleaseReview | null>(null);
  validReversalReason(): boolean { return ReversalReason.fromInput(this.reason) !== null; }
  ngOnInit(): void {
    this.store.list();
    this.destroyRef.onDestroy(() => this.store.leaveSelection());
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      const selection = SaleOperationSelection.fromWire(params.getAll('operationId'));
      if (!selection.equals(this.previousSelection)) this.resetOperationDrafts();
      this.previousSelection = selection;
      if (selection === SaleOperationSelection.None) { this.store.clearSelection(); return; }
      if (selection === SaleOperationSelection.Unknown || !selection.operationId) {
        this.store.rejectSelection(); this.focusPanelSoon(); return;
      }
      this.store.selectOperation(selection.operationId);
      this.focusPanelSoon();
    });
  }

  operationTriggerId(operationId: string): string { return `sale-operation-${operationId}`; }

  isOperationSelected(rawOperationId: string): boolean {
    const operationId = OperationId.fromWire(rawOperationId);
    return operationId?.equals(this.store.selectedOperationId()) === true;
  }

  selectOperation(rawOperationId: string): void {
    if (this.store.busy()) return;
    const operationId = OperationId.fromWire(rawOperationId);
    if (!operationId) { this.store.rejectSelection(); this.focusPanelSoon(); return; }
    this.returnFocusOperation = operationId.wire;
    void this.router.navigate([], { relativeTo: this.route, queryParams: { operationId: operationId.wire }, queryParamsHandling: 'merge' });
  }

  closeSelection(): void {
    const focusOperation = this.returnFocusOperation ?? this.store.selectedOperationId()?.wire ?? null;
    this.store.clearSelection(); this.resetOperationDrafts();
    void this.router.navigate([], { relativeTo: this.route, queryParams: { operationId: null }, queryParamsHandling: 'merge' }).then(() => {
      afterNextRender(() => {
        const host = this.host.nativeElement as HTMLElement;
        const trigger = focusOperation ? host.querySelector<HTMLElement>(`#${this.operationTriggerId(focusOperation)}`) : null;
        (trigger ?? host.querySelector<HTMLElement>('#sales-list-heading'))?.focus();
      }, { injector: this.injector });
    });
  }

  captureValidationMessage(): string | null {
    const sale = this.store.detail();
    if (!sale || !this.store.can(this.actions.CapturePayment)) return null;
    const amount = TicketMoney.fromDecimal(this.amount);
    if (!amount) return 'Ingresá un importe válido con hasta dos decimales.';
    if (amount.cents <= 0n) return 'El importe debe ser mayor que cero.';
    if (sale.pending && amount.cents > sale.pending.cents) return `El importe no puede superar el saldo ${this.formatAmount(sale.pending)}.`;
    return null;
  }

  capturePayment(): void {
    if (this.captureValidationMessage() !== null) return;
    this.store.execute(this.actions.CapturePayment, this.reason, this.amount, this.method);
  }
  requestReverse(paymentId: number): void {
    const sale = this.store.detail();
    const reason = ReversalReason.fromInput(this.reason);
    if (!sale || !reason || !this.store.can(this.actions.ReversePayment)) return;
    this.reversalReview.set(Object.freeze({ operationId: sale.identity.operationId, paymentId, reason }));
    this.reversalPhase.set(ConfirmationPhase.Reviewing);
  }
  cancelReverse(): void {
    this.reversalReview.set(null);
    this.reversalPhase.set(ConfirmationPhase.Idle);
  }
  reversalDescription(): string {
    const review = this.reversalReview();
    return review == null ? '' : `Vas a reversar el pago ${review.paymentId} de la venta abierta.`;
  }
  confirmReverse(): void {
    const review = this.reversalReview();
    if (!this.reversalPhase().isReviewing || !review || this.store.detail()?.identity.operationId !== review.operationId ||
        !this.store.can(this.actions.ReversePayment)) { this.cancelReverse(); return; }
    this.cancelReverse();
    this.store.execute(this.actions.ReversePayment, review.reason.wire, undefined, this.method, review.paymentId);
  }
  requestRelease(): void {
    const sale = this.store.detail();
    if (!sale || !this.store.can(this.actions.Release)) return;
    this.releaseReview.set(Object.freeze({ operationId: sale.identity.operationId, reason: this.reason.trim() }));
    this.releasePhase.set(ConfirmationPhase.Reviewing);
  }
  cancelRelease(): void { this.releaseReview.set(null); this.releasePhase.set(ConfirmationPhase.Idle); }
  confirmRelease(): void {
    const review = this.releaseReview();
    if (!this.releasePhase().isReviewing || !review || this.store.detail()?.identity.operationId !== review.operationId ||
        !this.store.can(this.actions.Release)) { this.cancelRelease(); return; }
    this.cancelRelease();
    this.store.execute(this.actions.Release, review.reason);
  }

  private resetOperationDrafts(): void {
    this.reason = ''; this.amount = ''; this.method = PaymentMethod.Cash;
    this.cancelReverse(); this.cancelRelease();
  }

  private focusPanelSoon(): void {
    afterNextRender(() => (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('#selected-operation-heading')?.focus(), { injector: this.injector });
  }
}
