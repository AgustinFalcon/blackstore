import { Injectable, computed, signal } from '@angular/core';
import { CartDraft, CartLine, CartPhase } from '../../core/domain/cart';

/** Owns the local draft only. Durable sale evidence remains authoritative elsewhere. */
@Injectable()
export class SaleCartStore {
  readonly draft = signal(CartDraft.empty());
  readonly phase = signal(CartPhase.Editing);
  readonly lineCount = computed(() => this.draft().lines.length);
  readonly unitCount = computed(() => this.draft().lines.reduce((sum, line) => sum + line.quantity, 0));

  add(line: CartLine): void {
    this.assertEditable();
    this.draft.update(current => current.add(line));
  }

  change(variantId: string, quantity: number): void {
    this.assertEditable();
    this.draft.update(current => current.change(variantId, quantity));
  }

  remove(variantId: string): void {
    this.assertEditable();
    this.draft.update(current => current.remove(variantId));
  }

  moveTo(phase: CartPhase): void { this.phase.set(phase); }

  reset(): void {
    this.draft.set(CartDraft.empty());
    this.phase.set(CartPhase.Editing);
  }

  private assertEditable(): void {
    if (!this.phase().editable) throw new Error('El carrito ya no se puede editar.');
  }
}
