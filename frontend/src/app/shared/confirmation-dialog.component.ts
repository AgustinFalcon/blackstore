import { Component, ElementRef, effect, input, output, viewChild } from '@angular/core';
import { ConfirmationPhase } from '../core/domain/confirmation-phase';

@Component({
  selector: 'bs-confirmation-dialog',
  standalone: true,
  template: `
    <dialog #dialog (cancel)="cancelFromKeyboard($event)" [attr.aria-labelledby]="titleId()">
      <h3 [id]="titleId()">{{ title() }}</h3>
      <p>{{ description() }}</p>
      <p>{{ detail() }}</p>
      <div class="actions">
        <button type="button" class="primary" autofocus (click)="confirmed.emit()">{{ confirmLabel() }}</button>
        <button type="button" class="ghost" (click)="cancelled.emit()">{{ cancelLabel() }}</button>
      </div>
    </dialog>
  `,
  styles: [`
    dialog {
      width: min(32rem, calc(100vw - 2rem));
      border: 1px solid var(--bs-warning);
      border-radius: 12px;
      padding: 1.25rem;
      color: var(--bs-ink);
      background: var(--bs-surface);
      box-shadow: 0 24px 60px rgb(15 23 42 / 28%);
    }
    dialog::backdrop { background: rgb(15 23 42 / 55%); }
    h3 { margin-top: 0; }
    .actions { display: flex; gap: 0.75rem; flex-wrap: wrap; }
    @media (max-width: 720px) {
      .actions button { width: 100%; }
    }
  `],
})
export class ConfirmationDialogComponent {
  readonly phase = input.required<ConfirmationPhase>();
  readonly title = input.required<string>();
  readonly titleId = input.required<string>();
  readonly description = input.required<string>();
  readonly detail = input.required<string>();
  readonly confirmLabel = input.required<string>();
  readonly cancelLabel = input.required<string>();
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    effect(() => {
      const element = this.dialog().nativeElement;
      if (this.phase().isReviewing && !element.open) element.showModal();
      if (!this.phase().isReviewing && element.open) element.close();
    });
  }

  cancelFromKeyboard(event: Event): void {
    event.preventDefault();
    this.cancelled.emit();
  }
}
