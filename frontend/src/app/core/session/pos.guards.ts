import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { StaffRole, roleCapabilities } from './staff-role';
import { PosSessionStore } from './pos-session.store';

export const sessionGuard: CanActivateFn = () => {
  const session = inject(PosSessionStore);
  if (session.actor()) return true;
  return inject(Router).createUrlTree(['/sesion']);
};

export function roleGuard(check: (role: StaffRole) => boolean): CanActivateFn {
  return () => {
    const session = inject(PosSessionStore);
    const router = inject(Router);
    const actor = session.actor();
    if (!actor) return router.createUrlTree(['/sesion']);
    if (check(actor.role)) {
      session.clearDenial();
      return true;
    }
    session.deny('Rol insuficiente para esa pantalla.');
    return router.createUrlTree(['/']);
  };
}

export const cashGuard = roleGuard((role) => roleCapabilities(role).cash);
export const sellGuard = roleGuard((role) => roleCapabilities(role).sell);
export const reportsGuard = roleGuard((role) => roleCapabilities(role).reports);
export const closeGuard = roleGuard((role) => roleCapabilities(role).close);
