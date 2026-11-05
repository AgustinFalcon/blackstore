import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, OnInit, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { concatMap, finalize, map, takeUntil, throwError } from 'rxjs';
import { AccountingCommand, AccountingCommandKind, CommandOutcome } from '../../core/domain/accounting-command';
import { CartLine, CartPhase } from '../../core/domain/cart';
import { ConfirmationPhase } from '../../core/domain/confirmation-phase';
import { DurableLineMetadata, DurableSaleDetail } from '../../core/domain/durable-sale';
import { PaymentMethod, PaymentStatus, SaleAction, SaleStatus } from '../../core/domain/pos-types';
import { ReversalReason } from '../../core/domain/reversal-reason';
import { SaleAdmissionOutcome, SaleCommand } from '../../core/domain/sale-command';
import { PaymentAttempt, PaymentCoverage, PaymentLedgerSemantics, TicketIdentity, TicketMoney, TicketSnapshot, TicketTransitionPolicy } from '../../core/domain/ticket-transition';
import { CommandHttp } from '../../core/infrastructure/command-http';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { AccountingCommandsStore } from '../../core/services/accounting-commands.store';
import { CounterContextService } from '../../core/services/counter-context.service';
import { DurableSalesStore } from '../../core/services/durable-sales.store';
import { SaleCommandsStore } from '../../core/services/sale-commands.store';
import { SessionStore } from '../../core/services/session.store';
import { StaffPermission } from '../../core/domain/session-types';
import { ConfirmationDialogComponent } from '../../shared/confirmation-dialog.component';
import { SaleCartStore } from './sale-cart.store';
import { AwaitReservationStep, AwaitTerminalSaleStep, CapturePaymentStep, CommitSaleStep, PrepareReservationStep,
  RefreshCoverageStep, RefreshTicketStep, TicketFlowPort, ValidateCartStep } from './ticket-steps';

interface ActiveTicket { readonly identity: TicketIdentity; }
interface TicketReversalReview { readonly operationId: string; readonly paymentId: number; readonly reason: ReversalReason; }
interface TicketPaymentRecord { readonly paymentId:number; readonly originalPaymentId:number|null; readonly status:PaymentStatus;
  readonly method:PaymentMethod; readonly amount:TicketMoney; readonly fee:TicketMoney|null; }

@Component({
  selector: 'bs-sale-ticket', standalone: true,
  imports: [FormsModule, ConfirmationDialogComponent], providers: [SaleCartStore],
  templateUrl: './sale-ticket.component.html', styleUrl: './sale-ticket.component.css',
})
export class SaleTicketComponent implements OnInit {
  readonly cart = inject(SaleCartStore);
  readonly commands = inject(AccountingCommandsStore);
  readonly sales = inject(SaleCommandsStore);
  readonly counter = inject(CounterContextService);
  private readonly commandHttp = inject(CommandHttp);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly durableSales = inject(DurableSalesStore);
  private readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly paymentMethods = PaymentMethod.selectable;
  readonly busy = signal(false);
  readonly snapshot = signal<TicketSnapshot | null>(null);
  readonly operationRef = signal<string | null>(null);
  readonly paymentId = signal<number | null>(null);
  readonly paymentHistory = signal<readonly TicketPaymentRecord[]>([]);
  readonly message = signal<string | null>(null);
  readonly announcement = signal('Carrito vacío.');
  readonly reversalPhase = signal(ConfirmationPhase.Idle);
  readonly releasePhase = signal(ConfirmationPhase.Idle);
  readonly reversalReview = signal<TicketReversalReview | null>(null);
  readonly cartPhases = [CartPhase.Editing, CartPhase.AwaitingReservation, CartPhase.AwaitingPayment, CartPhase.AwaitingCommit, CartPhase.Completed] as const;
  readonly commitAction = SaleAction.Commit;
  readonly releaseAction = SaleAction.Release;
  readonly reverseAction = SaleAction.Reverse;
  readonly reversalReasonGuidance = ReversalReason.guidance;

  selectedSku = '';
  operationReason = '';
  paymentMethod = PaymentMethod.Cash;
  paymentAmount = '';
  reversalReason = '';
  private active: ActiveTicket | null = null;
  readonly requestedOperationId = signal<string | null>(null);

  private readonly port: TicketFlowPort = {
    reserve: () => throwError(() => new Error('La reserva se prepara mediante el comando multiline.')),
    capture: () => throwError(() => new Error('El contrato de captura anterior está deshabilitado.')),
    capturePayment: attempt => {
      if (!this.session.can(StaffPermission.PaymentCapture) || attempt.fee.cents !== 0n) return throwError(() => new Error('Captura no autorizada o comisión no admitida.'));
      const command = AccountingCommand.create(AccountingCommandKind.Capture, {
        ...attempt.identity, paymentMethod: attempt.method.wire, amount: attempt.amount.decimal, reason: this.operationReason,
      });
      return this.commands.execute(command).pipe(map(receipt => {
        if (receipt.outcome !== CommandOutcome.Committed || !receipt.paymentId) throw new Error(receipt.failure.label || receipt.outcome.label);
        return Object.freeze({ paymentId: receipt.paymentId, status: PaymentStatus.Captured, amount: attempt.amount, fee: attempt.fee });
      }));
    },
    refresh: identity => this.commandHttp.ticket(identity),
  };
  private readonly refreshStep = new RefreshTicketStep(this.port, PaymentLedgerSemantics.Net);
  private readonly captureStep = new CapturePaymentStep(this.port);
  private readonly awaitReservation = new AwaitReservationStep(this.port, 20, 100, PaymentLedgerSemantics.Net);
  private readonly awaitTerminal = new AwaitTerminalSaleStep(this.port, 20, 100, PaymentLedgerSemantics.Net);
  private readonly validateCart = new ValidateCartStep();
  private readonly prepareReservation = new PrepareReservationStep();
  private readonly refreshCoverage = new RefreshCoverageStep(this.captureStep,this.refreshStep,(payment,attempt)=>{
    this.paymentId.set(payment.paymentId);
    this.paymentHistory.update(history=>Object.freeze([...history,Object.freeze({paymentId:payment.paymentId,originalPaymentId:null,
      status:payment.status,method:attempt.method,amount:payment.amount,fee:payment.fee})]));
  });
  private readonly completeSale = new CommitSaleStep(this.refreshStep,command=>this.sales.execute(command),this.awaitTerminal);

  private readonly selectFirstCatalogItem = effect(() => {
    const items = this.counter.catalog()?.items ?? [];
    if (!items.some(item => item.sku === this.selectedSku)) this.selectedSku = items[0]?.sku ?? '';
  });

  private readonly adoptDurableSale = effect(() => {
    const requested = this.requestedOperationId(), detail = this.durableSales.detail();
    if (requested && detail?.identity.operationId === requested) {
      if(this.active?.identity.operationId!==requested)this.openDurable(detail);
      else this.syncPaymentHistory(detail);
    }
    if (requested && this.durableSales.notice() && !this.durableSales.busy()) {
      this.cart.moveTo(CartPhase.RecoveryRequired);
      this.message.set(this.durableSales.notice());
    }
  });

  constructor() { this.session.changed.pipe(takeUntilDestroyed()).subscribe(() => this.clearForActorChange()); }
  ngOnInit(): void {
    this.counter.load();
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      const operationId = params.get('operationId');
      if (!operationId) { this.requestedOperationId.set(null); return; }
      this.requestedOperationId.set(operationId);
      if(this.active?.identity.operationId===operationId)return;
      this.cart.moveTo(CartPhase.AwaitingReservation);
      this.message.set('Comprobando la venta existente…');
      this.durableSales.open(operationId);
    });
  }

  phase(): CartPhase { return this.cart.phase(); }
  isEditing(): boolean { return this.phase() === CartPhase.Editing; }
  isPaymentPhase(): boolean { return this.phase() === CartPhase.Reserved || this.phase() === CartPhase.AwaitingPayment; }
  isRecovery(): boolean { return this.phase() === CartPhase.RecoveryRequired || this.phase() === CartPhase.Unknown; }
  selectedItem() { return this.counter.catalog()?.items.find(item => item.sku === this.selectedSku) ?? null; }
  phaseReached(phase: CartPhase): boolean { return this.cartPhases.indexOf(phase) <= this.cartPhases.indexOf(this.normalizedProgressPhase()); }

  addSelected(): void {
    if (!this.isEditing()) return;
    const item = this.selectedItem();
    if (!item || item.unitPrice == null || !item.priceVersion) { this.failDraft('El artículo no tiene precio o versión autorizados. Actualizá el catálogo.'); return; }
    try {
      this.cart.add(CartLine.create({ variantId: item.variantId, sku: item.sku, productName: item.name, quantity: 1,
        expectedPriceVersion: item.priceVersion, originalUnitPrice: String(item.unitPrice), discountAmount: '0.00' }));
      this.message.set(null);
      this.announcement.set(`${item.name} agregado. ${this.cart.unitCount()} unidades en el carrito.`);
      this.focusSoon('[data-cart-heading]');
    } catch (error) { this.failDraft(this.failureMessage(error)); }
  }

  changeQuantity(variantId: string, input: HTMLInputElement): void {
    try {
      this.cart.change(variantId, Number(input.value));
      input.removeAttribute('aria-invalid');
      this.announcement.set(`Cantidad actualizada. ${this.cart.unitCount()} unidades en el carrito.`);
    } catch (error) {
      const current=this.cart.draft().lines.find(line=>line.variantId===variantId);
      if(current)input.value=String(current.quantity);
      input.setAttribute('aria-invalid','true');input.focus();
      this.failDraft(this.failureMessage(error));
    }
  }

  removeLine(variantId: string, name: string): void {
    try {
      this.cart.remove(variantId);
      this.announcement.set(`${name} eliminado. ${this.cart.unitCount()} unidades en el carrito.`);
      this.focusSoon('#catalog-product');
    } catch (error) { this.failDraft(this.failureMessage(error)); }
  }

  reserve(): void {
    if (!TicketTransitionPolicy.beginReserve(this.busy(), this.blockReason() === null).permitsWrite || !this.isEditing()) return;
    let lines;
    try { lines = this.validateCart.execute(this.cart.draft()); }
    catch (error) { this.failDraft(this.failureMessage(error)); return; }
    const context = this.commands.runtime.context();
    const cashSession = this.counter.openSession();
    if (!context || !cashSession || cashSession.terminalId !== context.terminalId) { this.failDraft('Caja y contexto POS no coinciden.'); return; }
    this.busy.set(true);
    let command: SaleCommand;
    try { command = this.prepareReservation.execute(lines,{...context,cashSessionId:Number(cashSession.id),cashTerminalId:cashSession.terminalId},this.operationReason); }
    catch (error) { this.busy.set(false); this.failDraft(this.failureMessage(error)); return; }
    const active = Object.freeze({ identity: command.identity });
    this.active = active;
    this.operationRef.set(command.identity.operationId);
    this.requestedOperationId.set(command.identity.operationId);
    void this.router.navigate([], {relativeTo:this.route,queryParams:{operationId:command.identity.operationId},replaceUrl:true});
    this.snapshot.set(null);
    this.paymentId.set(null);
    this.paymentHistory.set([]);
    this.cart.moveTo(CartPhase.AwaitingReservation);
    this.announcement.set('Reserva enviada. Esperando comprobación autoritativa.');
    this.sales.execute(command).pipe(
      concatMap(result => result.outcome === SaleAdmissionOutcome.Accepted ? this.commandHttp.ticket(command.identity)
        : throwError(() => new Error(result.outcome.label))),
      map(response => PosWireMapper.ticket(response, command.identity, PaymentLedgerSemantics.Net)),
      concatMap(initial => this.awaitReservation.execute(command.identity, initial)),
      takeUntil(this.session.changed), takeUntilDestroyed(this.destroyRef),
      finalize(() => { if (this.active === active) this.busy.set(false); }),
    ).subscribe({
      next: snapshot => {
        if (this.active !== active) return;
        this.acceptSnapshot(snapshot);
        this.cart.moveTo(CartPhase.AwaitingPayment);
        this.paymentAmount = snapshot.pending?.decimal ?? '';
        this.message.set('Reserva comprobada. Elegí medio e importe para registrar el cobro.');
        this.announcement.set('Reserva comprobada. El cobro está habilitado.');
        this.focusSoon('#payment-amount');
      },
      error: error => this.enterRecovery(active, error),
    });
  }

  capture(): void {
    const active = this.active, snapshot = this.snapshot();
    if (!active || !snapshot || this.busy() || !this.isPaymentPhase()) return;
    const amount = TicketMoney.fromDecimal(this.paymentAmount), fee = TicketMoney.fromDecimal('0.00');
    if (!amount || !fee) { this.message.set('Ingresá un importe válido con hasta dos decimales.'); return; }
    const attempt: PaymentAttempt = Object.freeze({ identity: active.identity, method: this.paymentMethod, amount, fee });
    const decision = TicketTransitionPolicy.decide(snapshot, SaleAction.Capture, attempt);
    if (!decision.permitsWrite) { this.message.set(decision.label); return; }
    this.busy.set(true);
    this.refreshCoverage.execute(snapshot, attempt).pipe(
      map(result=>{this.snapshot.set(null);return result;}),
      takeUntil(this.session.changed), takeUntilDestroyed(this.destroyRef),
      finalize(() => { if (this.active === active) this.busy.set(false); }),
    ).subscribe({
      next: ({ payment, next }) => {
        if (this.active !== active) return;
        if(!next.pending){this.enterRecovery(active,new Error('El saldo consultado no es válido.'));return;}
        this.acceptSnapshot(next);
        if (next.coverage === PaymentCoverage.Paid && next.pending.cents === 0n) {
          this.cart.moveTo(CartPhase.Reserved); this.paymentAmount = '';
          this.message.set('Cobertura completa. Confirmá la venta para finalizar.');
          this.announcement.set('Pago completo comprobado. La venta está lista para confirmar.');
        } else {
          this.cart.moveTo(CartPhase.AwaitingPayment); this.paymentAmount = next.pending.decimal;
          this.message.set(`Pago registrado. Saldo pendiente ${this.formatMoney(next.pending.decimal)}.`);
          this.announcement.set('Pago parcial comprobado. Ingresá el siguiente cobro.');
          this.focusSoon('#payment-amount');
        }
      },
      error: error => this.enterRecovery(active, error),
    });
  }

  refresh(): void {
    const active = this.active;
    if (!active) {
      const requested=this.requestedOperationId();
      if(requested&&!this.durableSales.busy())this.durableSales.open(requested);
      return;
    }
    if (this.busy()) return;
    this.busy.set(true);
    this.refreshStep.execute(active.identity).pipe(
      takeUntil(this.session.changed), takeUntilDestroyed(this.destroyRef),
      finalize(() => { if (this.active === active) this.busy.set(false); }),
    ).subscribe({
      next: snapshot => {
        if (this.active !== active) return;
        this.acceptSnapshot(snapshot); this.derivePhase(snapshot);
        this.durableSales.open(active.identity.operationId);
        this.message.set(`Estado comprobado: ${snapshot.status.label}. ${snapshot.coverage.label}.`);
        this.announcement.set(this.message() ?? 'Estado actualizado.');
      },
      error: error => this.enterRecovery(active, error),
    });
  }

  commit(): void { this.finish(SaleAction.Commit); }
  requestRelease(): void { if (this.canFinish(SaleAction.Release)) this.releasePhase.set(ConfirmationPhase.Reviewing); }
  cancelRelease(): void { this.releasePhase.set(ConfirmationPhase.Idle); }
  confirmRelease(): void {
    if (!this.releasePhase().isReviewing || !this.canFinish(SaleAction.Release)) { this.cancelRelease(); return; }
    this.cancelRelease(); this.finish(SaleAction.Release);
  }

  canFinish(action: SaleAction): boolean {
    if (!this.commands.runtime.canWrite() || this.busy() || this.commands.unresolved() || this.commands.blocked().blocksWrites) return false;
    const permission = action === SaleAction.Commit ? StaffPermission.SaleCommit : action === SaleAction.Release
      ? StaffPermission.SaleRelease : action === SaleAction.Reverse ? StaffPermission.PaymentReverse : StaffPermission.Unknown;
    return this.session.can(permission) && TicketTransitionPolicy.decide(this.snapshot(), action).permitsWrite;
  }

  canCapture(): boolean {
    const active = this.active, snapshot = this.snapshot(), amount = TicketMoney.fromDecimal(this.paymentAmount), fee = TicketMoney.fromDecimal('0.00');
    if (!active || !snapshot || !amount || !fee || this.busy() || !this.isPaymentPhase() || !this.session.can(StaffPermission.PaymentCapture)) return false;
    return TicketTransitionPolicy.decide(snapshot, SaleAction.Capture,
      Object.freeze({ identity: active.identity, method: this.paymentMethod, amount, fee })).permitsWrite;
  }

  canReserve(): boolean {
    return this.isEditing() && this.cart.lineCount() > 0 && TicketTransitionPolicy.beginReserve(this.busy(), this.blockReason() === null).permitsWrite;
  }
  hasPendingBalance(): boolean { return (this.snapshot()?.pending?.cents ?? 0n) > 0n; }
  paymentValidationMessage():string|null {
    if(!this.isPaymentPhase()||!this.hasPendingBalance())return null;
    const amount=TicketMoney.fromDecimal(this.paymentAmount),pending=this.snapshot()?.pending;
    if(!amount)return 'Ingresá un importe válido con hasta dos decimales.';
    if(amount.cents<=0n)return 'El importe debe ser mayor que cero.';
    if(pending&&amount.cents>pending.cents)return `El importe no puede superar el saldo ${this.formatMoney(pending.decimal)}.`;
    return null;
  }
  announcePaymentValidation():void {const error=this.paymentValidationMessage();if(error)this.announcement.set(error);}
  completedTitle():string{return this.snapshot()?.status===SaleStatus.Released?'Reserva liberada':'Venta confirmada';}
  completedDescription():string{return this.snapshot()?.status===SaleStatus.Released?
    'El stock reservado volvió a quedar disponible.':'La operación quedó comprobada.';}

  startNewSale(): void {
    if (this.busy() || (this.active && this.phase() !== CartPhase.Completed)) return;
    this.active = null; this.snapshot.set(null); this.operationRef.set(null); this.paymentId.set(null); this.paymentHistory.set([]); this.requestedOperationId.set(null);
    this.message.set(null); this.paymentAmount = ''; this.cart.reset();
    void this.router.navigate(['/ticket']);
    this.announcement.set('Nueva venta lista.'); this.focusSoon('#catalog-product');
  }

  consultReceipt(): void {
    const active = this.active;
    const unresolved = this.commands.unresolved();
    if (!unresolved || this.busy()) return;
    this.busy.set(true);
    this.commands.consult().pipe(finalize(() => this.busy.set(false)), takeUntil(this.session.changed),
      takeUntilDestroyed(this.destroyRef)).subscribe(receipt => {
      this.snapshot.set(null);
      if (active && this.active === active && receipt.outcome === CommandOutcome.Committed && receipt.paymentId) {
        if (unresolved.kind === AccountingCommandKind.Capture) this.paymentId.set(receipt.paymentId);
        else if (unresolved.kind === AccountingCommandKind.Reverse) this.paymentId.set(null);
      }
      this.message.set(`${receipt.failure.label || receipt.outcome.label}. Consultá la venta antes de continuar.`);
      this.cart.moveTo(CartPhase.RecoveryRequired);
    });
  }

  consultSaleReceipt(): void {
    if (!this.sales.unresolved() || this.busy()) return;
    this.busy.set(true);
    this.sales.consult().pipe(finalize(() => this.busy.set(false)), takeUntil(this.session.changed),
      takeUntilDestroyed(this.destroyRef)).subscribe(result => {
      this.snapshot.set(null); this.message.set(`${result.outcome.label}. Consultá la venta antes de continuar.`);
      this.cart.moveTo(CartPhase.RecoveryRequired);
    });
  }

  validReversalReason(): boolean { return ReversalReason.fromInput(this.reversalReason) !== null; }
  requestReverse(paymentId: number): void {
    const reason = ReversalReason.fromInput(this.reversalReason);
    if (!this.active || !reason || paymentId !== this.paymentId() || !this.canFinish(SaleAction.Reverse)) return;
    this.reversalReview.set(Object.freeze({ operationId: this.active.identity.operationId, paymentId, reason }));
    this.reversalPhase.set(ConfirmationPhase.Reviewing);
  }
  cancelReverse(): void { this.reversalReview.set(null); this.reversalPhase.set(ConfirmationPhase.Idle); }
  reversalDescription(): string {
    const review = this.reversalReview();
    return review ? `Vas a reversar el pago ${review.paymentId}. La acción queda auditada.` : '';
  }
  confirmReverse(): void {
    const review = this.reversalReview(), active = this.active;
    if (!active || !review || !this.reversalPhase().isReviewing || review.operationId !== active.identity.operationId || !this.canFinish(SaleAction.Reverse)) {
      this.cancelReverse(); return;
    }
    this.cancelReverse(); this.busy.set(true);
    this.commands.execute(AccountingCommand.create(AccountingCommandKind.Reverse, {
      ...active.identity, originalPaymentId: review.paymentId, reason: review.reason.wire, evidenceRef: `rev-${review.paymentId}`,
    }, review.paymentId)).pipe(takeUntil(this.session.changed), takeUntilDestroyed(this.destroyRef),
      finalize(() => { if (this.active === active) this.busy.set(false); })).subscribe(receipt => {
      if (this.active !== active) return;
      if (receipt.outcome === CommandOutcome.Committed) {
        const captured=this.paymentHistory().find(payment=>payment.paymentId===review.paymentId && payment.status===PaymentStatus.Captured);
        if(captured&&receipt.paymentId)this.paymentHistory.update(history=>Object.freeze([...history,Object.freeze({paymentId:receipt.paymentId!,
          originalPaymentId:review.paymentId,status:PaymentStatus.Refunded,method:captured.method,amount:captured.amount,fee:null})]));
        this.paymentId.set(null);
      }
      this.snapshot.set(null); this.cart.moveTo(CartPhase.RecoveryRequired);
      this.message.set(`${receipt.failure.label || receipt.outcome.label}. Consultá la venta para comprobar el saldo.`);
    });
  }

  blockReason(): string | null {
    if (!this.commands.runtime.canWrite()) return this.commands.runtime.notice();
    if (this.commands.unresolved() || this.sales.unresolved()) return 'Hay un comando pendiente. Consultá su recibo antes de operar.';
    if (this.commands.blocked().blocksWrites) return this.commands.blocked().label;
    if (!this.session.can(StaffPermission.SaleReserve)) return 'Permiso insuficiente para reservar ventas.';
    return this.counter.blockReason();
  }

  formatMoney(raw: string | number | null | undefined): string {
    const amount = Number(raw);
    return Number.isFinite(amount) ? new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' }).format(amount) : 'No disponible';
  }

  private finish(action: SaleAction): void {
    const active = this.active;
    if (!active || !this.canFinish(action)) return;
    this.busy.set(true); this.cart.moveTo(CartPhase.AwaitingCommit);
    this.completeSale.execute(active.identity,Number(this.counter.openSession()?.id),this.operationReason,action).pipe(
      takeUntil(this.session.changed), takeUntilDestroyed(this.destroyRef),
      finalize(() => { if (this.active === active) this.busy.set(false); }),
    ).subscribe({
      next: snapshot => {
        if (this.active !== active) return;
        this.acceptSnapshot(snapshot);
        if (snapshot.status === SaleStatus.Committed || snapshot.status === SaleStatus.Released) {
          this.cart.moveTo(CartPhase.Completed); this.message.set(`${action.resultSubject} ${snapshot.status.label}.`);
          this.announcement.set('Operación finalizada y comprobada.');
        } else {
          this.cart.moveTo(CartPhase.RecoveryRequired);
          this.message.set('La operación fue admitida pero aún no está finalizada. Consultá nuevamente.');
        }
      },
      error: error => this.enterRecovery(active, error),
    });
  }

  private acceptSnapshot(snapshot: TicketSnapshot): void {
    if (!snapshot.evidenceValid) throw new Error('No se pudo comprobar la identidad o evidencia de la venta.');
    this.snapshot.set(snapshot);
  }

  private derivePhase(snapshot: TicketSnapshot): void {
    if (!snapshot.evidenceValid || snapshot.status === SaleStatus.Unknown || snapshot.coverage === PaymentCoverage.InvalidUnknown) {
      this.cart.moveTo(CartPhase.Unknown); return;
    }
    if (snapshot.status === SaleStatus.Committed || snapshot.status === SaleStatus.Released) this.cart.moveTo(CartPhase.Completed);
    else if (snapshot.status === SaleStatus.CommitPending || snapshot.status === SaleStatus.ReleasePending) this.cart.moveTo(CartPhase.AwaitingCommit);
    else if (snapshot.pending?.cents === 0n && snapshot.coverage === PaymentCoverage.Paid) this.cart.moveTo(CartPhase.Reserved);
    else if (snapshot.status === SaleStatus.Reserved || snapshot.status === SaleStatus.PaymentCaptured) this.cart.moveTo(CartPhase.AwaitingPayment);
    else this.cart.moveTo(CartPhase.RecoveryRequired);
  }

  private openDurable(detail: DurableSaleDetail): void {
    const completeLines = detail.lines.every(line => line.metadata === DurableLineMetadata.Complete && line.variantId !== null &&
      line.originalUnitPrice !== null && line.priceVersion !== null);
    this.cart.reset();
    if (completeLines) {
      try {
        for (const line of detail.lines) this.cart.add(CartLine.create({
          variantId: line.variantId!, sku: line.sku, productName: line.productName, quantity: line.quantity,
          expectedPriceVersion: line.priceVersion!, originalUnitPrice: line.originalUnitPrice!.decimal, discountAmount: '0.00',
        }));
      } catch {
        this.cart.reset();
        this.cart.moveTo(CartPhase.RecoveryRequired);
        this.message.set('La venta contiene líneas que no se pueden comprobar. Sólo está disponible para consulta.');
        return;
      }
    }
    const snapshot: TicketSnapshot = Object.freeze({
      ledgerSemantics: PaymentLedgerSemantics.Net,
      identity: detail.identity,
      status: SaleStatus.fromWire(detail.status.wire),
      evidenceValid: detail.valid && completeLines,
      receipt: detail.receipt,
      reservationRef: detail.reservationRef,
      total: detail.total,
      pending: detail.pending,
      coverage: detail.coverage,
      hasPaymentHistory: detail.hasPaymentHistory,
      blocked: !detail.valid || !completeLines,
      retired: false,
    });
    this.active = Object.freeze({ identity: detail.identity });
    this.operationRef.set(detail.identity.operationId);
    this.snapshot.set(snapshot);
    this.syncPaymentHistory(detail);
    this.paymentAmount = detail.pending && detail.pending.cents > 0n ? detail.pending.decimal : '';
    this.derivePhase(snapshot);
    this.message.set(snapshot.evidenceValid
      ? `Venta reabierta y comprobada: ${snapshot.status.label}. ${snapshot.coverage.label}.`
      : 'La venta histórica no tiene metadatos completos. Sólo está disponible para consulta.');
    this.announcement.set(this.message() ?? 'Venta reabierta.');
  }

  private syncPaymentHistory(detail:DurableSaleDetail):void {
    const reversible=detail.payments.filter(payment=>payment.status===PaymentStatus.Captured&&payment.reversibility.permitsReverse);
    this.paymentId.set(reversible.length?reversible[reversible.length-1].paymentId:null);
    this.paymentHistory.set(Object.freeze(detail.payments.filter(payment=>payment.amount!==null).map(payment=>Object.freeze({
      paymentId:payment.paymentId,originalPaymentId:payment.originalPaymentId,status:payment.status,method:payment.method,
      amount:payment.amount!,fee:payment.fee,
    }))));
  }

  private normalizedProgressPhase(): CartPhase {
    const phase = this.phase();
    if (phase === CartPhase.Reserved) return CartPhase.AwaitingCommit;
    if (phase === CartPhase.RecoveryRequired || phase === CartPhase.Unknown) return CartPhase.AwaitingReservation;
    return phase;
  }

  private enterRecovery(active: ActiveTicket, error: unknown): void {
    if (this.active !== active) return;
    this.snapshot.set(null); this.cart.moveTo(CartPhase.RecoveryRequired);
    this.message.set(this.failureMessage(error));
    this.announcement.set('No se pudo comprobar la operación. Se requiere recuperación.');
    this.focusSoon('[data-operation-notice]');
  }

  private failDraft(message: string): void { this.message.set(message); this.announcement.set(message); this.focusSoon('[data-operation-notice]'); }
  private failureMessage(error: unknown): string {
    return error instanceof Error && !(error instanceof HttpErrorResponse) ? error.message : 'No se pudo comprobar la operación. Consultá su estado.';
  }
  private clearForActorChange(): void {
    this.active = null; this.snapshot.set(null); this.operationRef.set(null); this.paymentId.set(null); this.paymentHistory.set([]); this.requestedOperationId.set(null);
    this.message.set(null); this.busy.set(false); this.cart.reset(); this.cancelReverse(); this.cancelRelease();
  }
  private focusSoon(selector: string): void { setTimeout(() => (this.host.nativeElement.querySelector(selector) as HTMLElement | null)?.focus()); }
}
