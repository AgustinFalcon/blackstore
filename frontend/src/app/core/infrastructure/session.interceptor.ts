import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { EMPTY, catchError, filter, takeUntil, throwError } from 'rxjs';
import { ACCOUNTING_API_BASE, API_BASE } from '../api';
import { SessionStore } from '../services/session.store';

export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  if (![API_BASE, ACCOUNTING_API_BASE].some(base => request.url === base || request.url.startsWith(`${base}/`))) return next(request);
  const session = inject(SessionStore);
  const generation = session.generation();
  const mutation = !['GET', 'HEAD', 'OPTIONS'].includes(request.method);
  const token = request.headers.get('X-CSRF-Token') ?? session.csrfToken();
  let headers = request.headers.delete('X-Actor-Id').delete('X-Role');
  if (mutation && token) headers = headers.set('X-CSRF-Token', token);
  const auth = request.url.startsWith(`${API_BASE}/auth/`);
  if (mutation && !token) return throwError(() => new Error('Comprobá la sesión antes de continuar.'));
  return next(request.clone({ withCredentials: true, headers })).pipe(
    takeUntil(session.changed),
    filter(() => generation === session.generation()),
    catchError(error => {
      if (generation !== session.generation()) return EMPTY;
      if (!auth && error instanceof HttpErrorResponse && error.status === 401) session.expire();
      if (error instanceof HttpErrorResponse && error.status === 403) session.forbidden();
      return throwError(() => error);
    }),
  );
};
