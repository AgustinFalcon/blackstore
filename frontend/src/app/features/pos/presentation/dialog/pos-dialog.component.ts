import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, HostListener, OnDestroy, inject, input, output } from '@angular/core';

@Component({
  selector: 'bs-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pos-dialog.component.html',
  styleUrl: './pos-dialog.component.scss',
})
export class PosDialogComponent implements AfterViewInit, OnDestroy {
  private readonly host = inject(ElementRef<HTMLElement>);
  private previouslyFocused: HTMLElement | null = null;

  readonly title = input.required<string>();
  readonly titleId = input('bs-dialog-title');
  readonly primaryLabel = input<string | null>(null);
  readonly cancelLabel = input<string | null>(null);
  readonly danger = input(false);
  readonly primaryDisabled = input(false);
  readonly confirmed = output<void>();
  readonly dismissed = output<void>();

  ngAfterViewInit(): void {
    this.previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusable = this.focusable();
    (focusable[0] ?? this.dialog())?.focus();
  }

  ngOnDestroy(): void {
    this.previouslyFocused?.focus();
  }

  @HostListener('document:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.dismissed.emit();
      return;
    }
    if (event.key !== 'Tab') return;
    const nodes = this.focusable();
    if (nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private dialog(): HTMLElement | null {
    return this.root().querySelector('[role="dialog"]');
  }

  private focusable(): HTMLElement[] {
    const root = this.dialog();
    if (!root) return [];
    return Array.from(root.querySelectorAll<HTMLElement>('button, [href], input, select, textarea')).filter(
      (element) => !element.hasAttribute('disabled'),
    );
  }

  private root(): HTMLElement {
    return this.host.nativeElement;
  }
}
