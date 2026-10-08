import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ACCOUNTING_API_BASE } from '../api';
import { StaffRole } from '../domain/pos-types';
import { SessionStore } from '../services/session.store';
import { authenticateTestSession } from '../services/session-test-helper';
import { sessionInterceptor } from './session.interceptor';

describe('Accounting SID interceptor', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([sessionInterceptor])), provideHttpClientTesting()] }));
  it('uses the SID cookie and removes spoofed actor headers for v2 reads', () => {
    const identity = TestBed.inject(SessionStore); authenticateTestSession(identity, StaffRole.Owner);
    TestBed.inject(HttpClient).get(`${ACCOUNTING_API_BASE}/reports/shift`, { headers: { 'X-Actor-Id': '99', 'X-Role': StaffRole.Owner.wire } }).subscribe();
    const http = TestBed.inject(HttpTestingController); const request = http.expectOne(`${ACCOUNTING_API_BASE}/reports/shift`);
    expect(request.request.withCredentials).toBeTrue(); expect(request.request.headers.has('X-Actor-Id')).toBeFalse(); expect(request.request.headers.has('X-Role')).toBeFalse();
    request.flush({}); http.verify();
  });
  it('cancels v2 response delivery when the current identity expires', () => {
    const identity = TestBed.inject(SessionStore); authenticateTestSession(identity, StaffRole.Owner);
    let delivered = false;
    TestBed.inject(HttpClient).get(`${ACCOUNTING_API_BASE}/reports/day`).subscribe(() => delivered = true);
    const http = TestBed.inject(HttpTestingController); const request = http.expectOne(`${ACCOUNTING_API_BASE}/reports/day`);
    identity.generation.update(value => value + 1); identity.changed.next();
    expect(request.cancelled).toBeTrue(); expect(delivered).toBeFalse(); http.verify();
  });
});
