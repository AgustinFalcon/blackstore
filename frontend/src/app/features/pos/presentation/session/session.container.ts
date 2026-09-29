import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { PosSessionStore } from '../../../../core/session/pos-session.store';
import { StaffRole } from '../../../../core/session/staff-role';
import { PosStore } from '../../application/pos.store';
import { SessionViewComponent } from './session.view';

@Component({
  selector: 'bs-session-page',
  standalone: true,
  imports: [SessionViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<bs-session-view [actor]="session.actor()" (enter)="enter($event)" (leave)="leave()" />`,
})
export class SessionContainerComponent {
  readonly session = inject(PosSessionStore);
  private readonly store = inject(PosStore);
  private readonly router = inject(Router);

  enter(input: { readonly actorId: number; readonly role: StaffRole }): void {
    const current = this.session.actor();
    const same = current?.actorId === input.actorId && current.role === input.role;
    this.session.signIn(input.actorId, input.role);
    if (!same) this.store.resetForActor();
    void this.router.navigate(['/']);
  }

  leave(): void {
    this.session.signOut();
    this.store.resetForActor();
  }
}
