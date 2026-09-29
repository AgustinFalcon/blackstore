import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { formatEfectivo } from '../../domain/money';
import { PosPaymentMethod } from '../../domain/closed-status';
import { CatalogItem, SaleSnapshot, TicketLineDraft, TicketPrompt } from '../../domain/pos.models';
import { PosDialogComponent } from '../dialog/pos-dialog.component';

export interface TicketViewState {
  readonly loading: boolean;
  readonly items: readonly CatalogItem[];
  readonly blockReason: string | null;
  readonly reserveDisabled: boolean;
  readonly reserveLabel: string;
  readonly sessionId: number | null;
  readonly sale: SaleSnapshot | null;
  readonly busy: boolean;
  readonly error: string | null;
  readonly message: string | null;
  readonly prompt: TicketPrompt | null;
  readonly writesDisabled: boolean;
  readonly canCommit: boolean;
  readonly canRelease: boolean;
  readonly canRetryPay: boolean;
}

export interface ReserveRequest {
  readonly line: TicketLineDraft;
  readonly cashAmount: number;
  readonly feeAmount: number;
  readonly secondMethod: PosPaymentMethod;
  readonly secondAmount: number;
}

@Component({
  selector: 'bs-ticket-view',
  standalone: true,
  imports: [FormsModule, RouterLink, PosDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ticket.view.html',
})
export class TicketViewComponent {
  readonly state = input.required<TicketViewState>();
  readonly reserve = output<ReserveRequest>();
  readonly retryPay = output<ReserveRequest>();
  readonly refresh = output<void>();
  readonly commit = output<void>();
  readonly release = output<void>();
  readonly reverse = output<{ readonly paymentId: number; readonly reason: string }>();
  readonly dismissPrompt = output<void>();
  readonly goCatalog = output<void>();
  readonly format = formatEfectivo;
  readonly keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'] as const;
  readonly methods = PosPaymentMethod.known;
  readonly dialog = signal<'commit' | 'release' | 'reverse' | 'stock' | 'stale' | 'fiscal' | null>(null);
  readonly keypadTarget = signal<'amount' | 'quantity'>('amount');

  sku = '';
  productName = '';
  variantId = '';
  priceVersion = 'price-v1';
  quantity = 1;
  originalUnitPrice = 0;
  discountAmount = 0;
  amount = 0;
  fee = 0;
  secondMethodCode = PosPaymentMethod.Card.code;
  secondAmount = 0;
  reversalReason = '';
  reversePaymentId: number | null = null;
  private skuReady = false;
  private seenPrompt = 0;

  constructor() {
    effect(() => {
      const items = this.state().items;
      if (this.skuReady || items.length === 0) return;
      this.skuReady = true;
      this.applySku(items[0].sku);
    });
    effect(() => {
      const prompt = this.state().prompt;
      if (!prompt || prompt.token === this.seenPrompt) return;
      this.seenPrompt = prompt.token;
      this.dialog.set(prompt.kind);
    });
  }

  applySku(sku: string): void {
    const item = this.state().items.find((row) => row.sku === sku);
    if (!item) return;
    this.sku = item.sku;
    this.productName = item.name;
    this.variantId = item.variantId;
    this.priceVersion = item.priceVersion?.trim() || 'price-v1';
    if (item.unitPrice != null) {
      this.originalUnitPrice = item.unitPrice;
      this.amount = Math.max(0, item.unitPrice - Number(this.discountAmount));
    }
  }

  effective(): number {
    return Math.max(0, Number(this.originalUnitPrice) - Number(this.discountAmount));
  }

  collected(): number {
    return Number(this.amount) + (Number(this.secondAmount) || 0);
  }

  lineReady(): boolean {
    return this.sku.trim().length > 0 && this.productName.trim().length > 0 && Number(this.quantity) >= 1 && Number(this.discountAmount) <= Number(this.originalUnitPrice) && Number(this.amount) > 0;
  }

  request(): ReserveRequest {
    return {
      line: {
        sku: this.sku,
        productName: this.productName,
        variantId: this.variantId,
        priceVersion: this.priceVersion,
        quantity: Number(this.quantity),
        originalUnitPrice: Number(this.originalUnitPrice),
        discountAmount: Number(this.discountAmount),
      },
      cashAmount: Number(this.amount),
      feeAmount: Number(this.fee),
      secondMethod: PosPaymentMethod.fromWire(this.secondMethodCode),
      secondAmount: Number(this.secondAmount) || 0,
    };
  }

  appendKey(key: string): void {
    if (this.keypadTarget() === 'quantity') {
      const current = String(this.quantity ?? 0);
      this.quantity = Number(current === '0' ? key : current + key);
      return;
    }
    const current = String(this.amount ?? 0);
    this.amount = Number(current === '0' ? key : current + key);
  }

  clearKeypad(): void {
    if (this.keypadTarget() === 'quantity') this.quantity = 0;
    else this.amount = 0;
  }

  openReverse(paymentId: number): void {
    this.reversePaymentId = paymentId;
    this.reversalReason = '';
    this.dialog.set('reverse');
  }

  confirmReverse(): void {
    if (this.reversePaymentId == null || this.reversalReason.trim().length === 0) return;
    this.reverse.emit({ paymentId: this.reversePaymentId, reason: this.reversalReason.trim() });
    this.dialog.set(null);
  }

  closePrompt(): void {
    this.dialog.set(null);
    this.dismissPrompt.emit();
  }
}
