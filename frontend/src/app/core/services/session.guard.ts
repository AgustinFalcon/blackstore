import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { StaffPermission } from '../domain/session-types';
import { SessionStore } from './session.store';

export const sessionGuard: CanActivateFn = async route => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.bootstrap();
  if (!session.state().isAuthenticated) return router.parseUrl('/sesion');
  const permission = route.data['permission'];
  if (!(permission instanceof StaffPermission) || !session.can(permission)) {
    session.forbidden();
    return router.parseUrl(session.landing());
  }
  return true;
};
