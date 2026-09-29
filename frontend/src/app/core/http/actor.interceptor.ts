import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { API_BASE } from '../api';
import { PosSessionStore } from '../session/pos-session.store';

export const actorInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(API_BASE)) return next(req);
  const actor = inject(PosSessionStore).actor();
  const headers: Record<string, string> = { 'X-Trace-Id': crypto.randomUUID() };
  if (actor) {
    headers['X-Actor-Id'] = String(actor.actorId);
    headers['X-Role'] = actor.role.code;
  }
  return next(req.clone({ setHeaders: headers }));
};
