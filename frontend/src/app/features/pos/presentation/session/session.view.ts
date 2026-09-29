import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StaffRole } from '../../../../core/session/staff-role';
import { PosActor } from '../../../../core/session/pos-session.store';

@Component({
  selector: 'bs-session-view',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './session.view.html',
})
export class SessionViewComponent {
  readonly actor = input<PosActor | null>(null);
  readonly enter = output<{ readonly actorId: number; readonly role: StaffRole }>();
  readonly leave = output<void>();
  readonly roles = StaffRole.known;
  actorId: number | null = null;
  roleCode = StaffRole.Cashier.code;

  submit(): void {
    if (!this.canSubmit() || this.actorId == null) return;
    const role = StaffRole.fromWire(this.roleCode);
    if (role === StaffRole.Unknown) return;
    this.enter.emit({ actorId: this.actorId, role });
  }

  canSubmit(): boolean {
    return this.actorId != null && Number.isInteger(Number(this.actorId)) && Number(this.actorId) > 0;
  }
}
