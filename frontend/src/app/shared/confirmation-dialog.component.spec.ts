import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfirmationPhase } from '../core/domain/confirmation-phase';
import { ConfirmationDialogComponent } from './confirmation-dialog.component';

@Component({
  standalone: true,
  imports: [ConfirmationDialogComponent],
  template: `
    <button id="origin" type="button" (click)="phase.set(phases.Reviewing)">Abrir</button>
    <bs-confirmation-dialog [phase]="phase()" title="Confirmar" titleId="confirm-test-title"
      description="Descripción" detail="Detalle" confirmLabel="Confirmar" cancelLabel="Cancelar"
      (confirmed)="confirm()" (cancelled)="cancel()" />
  `,
})
class HostComponent {
  readonly phases = ConfirmationPhase;
  readonly phase = signal(ConfirmationPhase.Idle);
  confirmed = 0;
  cancelled = 0;
  confirm(): void { this.confirmed++; }
  cancel(): void { this.cancelled++; this.phase.set(ConfirmationPhase.Idle); }
}

describe('ConfirmationDialogComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('opens modally and cancels safely from the native Escape event', () => {
    const origin = fixture.nativeElement.querySelector('#origin') as HTMLButtonElement;
    origin.click();
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();

    dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.cancelled).toBe(1);
    expect(fixture.componentInstance.confirmed).toBe(0);
    expect(dialog.open).toBeFalse();
  });
});
