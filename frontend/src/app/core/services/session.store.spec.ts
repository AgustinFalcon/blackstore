import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { API_BASE } from '../api';
import { StaffRole } from '../domain/pos-types';
import { SessionState } from '../domain/session-types';
import { sessionInterceptor } from '../infrastructure/session.interceptor';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';

describe('SessionStore with own API interceptor', () => {
  let session: SessionStore;
  let http: HttpTestingController;
  const staff = { id: 7, displayName: 'Staff real', role: StaffRole.Cashier.wire };
  const envelope = (data: unknown) => ({ code: 200, traceId: 'test', data, message: null, errorCode: null, retryable: null });
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(withInterceptors([sessionInterceptor])), provideHttpClientTesting()] });
    session = TestBed.inject(SessionStore); http = TestBed.inject(HttpTestingController);
    spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
  });
  afterEach(() => http.verify());
  // fetchCsrf and its caller both resume asynchronously. The next event-loop
  // turn runs after the entire microtask queue, rather than after just one await.
  async function settleAuthContinuations(): Promise<void> {
    await new Promise<void>(resolve => setTimeout(resolve, 0));
  }
  async function login(): Promise<void> {
    const pending = session.login('staff', 'secret');
    http.expectOne(`${API_BASE}/auth/csrf`).flush(envelope({ csrfToken: 'pre-context' }));
    await settleAuthContinuations();
    const request = http.expectOne(`${API_BASE}/auth/login`);
    expect(request.request.withCredentials).toBeTrue();
    expect(request.request.headers.get('X-CSRF-Token')).toBe('pre-context');
    request.flush(envelope({ staff, csrfToken: 'authenticated-context' }));
    expect(await pending).toBeTrue();
  }
  it('bootstraps reload with cookie and session-bound CSRF before authentication', async () => {
    const pending = session.bootstrap();
    expect(session.state()).toBe(SessionState.Loading);
    const request = http.expectOne(`${API_BASE}/auth/session`);
    expect(request.request.withCredentials).toBeTrue(); request.flush(envelope({ staff }));
    await settleAuthContinuations();
    expect(session.state()).toBe(SessionState.Loading);
    http.expectOne(`${API_BASE}/auth/csrf`).flush(envelope({ csrfToken: 'session-context' }));
    await pending;
    expect(session.state()).toBe(SessionState.Authenticated);
    expect(session.staff()?.displayName).toBe('Staff real');
  });
  it('remains anonymous on 401 and unavailable on network failure', async () => {
    const initial = session.bootstrap();
    http.expectOne(`${API_BASE}/auth/session`).flush({}, { status: 401, statusText: 'Unauthorized' });
    await initial; expect(session.state()).toBe(SessionState.Anonymous);
    const retry = session.bootstrap(true);
    http.expectOne(`${API_BASE}/auth/session`).error(new ProgressEvent('offline'));
    await retry; expect(session.state()).toBe(SessionState.Unavailable);
  });
  it('rejects unknown server role without opening authenticated UI', async () => {
    const pending = session.bootstrap();
    http.expectOne(`${API_BASE}/auth/session`).flush(envelope({ staff: { ...staff, role: 'future-role' } }));
    await pending; expect(session.state()).toBe(SessionState.Unknown); expect(session.staff()).toBeNull();
    http.expectNone(`${API_BASE}/auth/csrf`);
  });
  it('rotates CSRF on login, strips forged authority and sends no automatic payment retry', async () => {
    await login();
    const client = TestBed.inject(HttpClient);
    let failed = false;
    client.post(`${API_BASE}/payments`, {}, { headers: { 'X-Actor-Id': '1', 'X-Role': StaffRole.Owner.wire } }).subscribe({ error: () => failed = true });
    const payment = http.expectOne(`${API_BASE}/payments`);
    expect(payment.request.headers.get('X-CSRF-Token')).toBe('authenticated-context');
    expect(payment.request.headers.has('X-Actor-Id')).toBeFalse();
    expect(payment.request.headers.has('X-Role')).toBeFalse();
    payment.flush({}, { status: 403, statusText: 'Forbidden' });
    expect(failed).toBeTrue(); expect(session.state()).toBe(SessionState.Authenticated);
    expect(session.notice()).toContain('Permiso insuficiente'); http.expectNone(`${API_BASE}/payments`);
  });
  it('cancels pending private responses and clears session identity immediately at logout', async () => {
    await login();
    let received = false;
    TestBed.inject(HttpClient).get(`${API_BASE}/catalog`).subscribe(() => received = true);
    const catalog = http.expectOne(`${API_BASE}/catalog`);
    const generation = session.generation();
    const pending = session.logout();
    expect(session.generation()).toBeGreaterThan(generation);
    expect(session.staff()).toBeNull(); expect(catalog.cancelled).toBeTrue(); expect(received).toBeFalse();
    const logout = http.expectOne(`${API_BASE}/auth/logout`);
    expect(logout.request.headers.get('X-CSRF-Token')).toBe('authenticated-context');
    logout.flush(null, { status: 204, statusText: 'No Content' });
    await pending; expect(session.state()).toBe(SessionState.Anonymous); expect(session.csrfToken()).toBeNull();
  });
  it('does not send cookies or CSRF to another origin', async () => {
    await login();
    TestBed.inject(HttpClient).post('https://other.example/api/v1/payments', {}).subscribe();
    const external = http.expectOne('https://other.example/api/v1/payments');
    expect(external.request.withCredentials).toBeFalse(); expect(external.request.headers.has('X-CSRF-Token')).toBeFalse(); external.flush({});
  });
  it('expires on private 401 and blocks mutation without a CSRF context', () => {
    authenticateTestSession(session);
    const client = TestBed.inject(HttpClient);
    client.get(`${API_BASE}/catalog`).subscribe({ error: () => {} });
    http.expectOne(`${API_BASE}/catalog`).flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(session.state()).toBe(SessionState.Expired);
    let blocked = false;
    client.post(`${API_BASE}/payments`, {}).subscribe({ error: () => blocked = true });
    expect(blocked).toBeTrue(); http.expectNone(`${API_BASE}/payments`);
  });
});
