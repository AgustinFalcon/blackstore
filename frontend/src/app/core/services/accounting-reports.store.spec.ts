import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingReportRequest, ReportFailure } from '../domain/accounting-report';
import { ReportPeriod, StaffRole } from '../domain/pos-types';
import { reportEnvelope, reportFixture } from '../infrastructure/accounting-report-test-helper';
import { AccountingReportsStore } from './accounting-reports.store';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';

describe('AccountingReportsStore', () => {
  let store: AccountingReportsStore; let identity: SessionStore; let http: HttpTestingController;
  const shift = AccountingReportRequest.shift(12)!;
  const day = AccountingReportRequest.day('2026-10-07', 'America/Argentina/Buenos_Aires', { cashierId: 7 })!;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), AccountingReportsStore] });
    identity = TestBed.inject(SessionStore); authenticateTestSession(identity, StaffRole.Owner);
    store = TestBed.inject(AccountingReportsStore); http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { store.clear(); http.verify(); });
  it('uses only v2 typed GET queries and sends explicit SHIFT/DAY filters', () => {
    store.shift.load(shift); store.day.load(day);
    const shiftRead = http.expectOne(request => request.url === `${ACCOUNTING_API_BASE}/reports/shift`);
    expect(shiftRead.request.method).toBe('GET'); expect(shiftRead.request.params.get('cashSessionId')).toBe('12');
    const dayRead = http.expectOne(request => request.url === `${ACCOUNTING_API_BASE}/reports/day`);
    expect(dayRead.request.params.get('localDate')).toBe('2026-10-07'); expect(dayRead.request.params.get('cashierId')).toBe('7');
    shiftRead.flush(reportEnvelope(reportFixture())); dayRead.flush(reportEnvelope(reportFixture(ReportPeriod.Day)));
    expect(store.shift.report()).not.toBeNull(); expect(store.day.report()).not.toBeNull();
  });
  it('cancels an earlier period request and clears its report while preserving the other period', () => {
    store.day.load(day); http.expectOne(request => request.url.endsWith('/day')).flush(reportEnvelope(reportFixture(ReportPeriod.Day)));
    store.shift.load(shift); const old = http.expectOne(request => request.url.endsWith('/shift'));
    store.shift.load(AccountingReportRequest.shift(99)!);
    expect(old.cancelled).toBeTrue(); expect(store.shift.report()).toBeNull(); expect(store.day.report()).not.toBeNull();
    const next = http.expectOne(request => request.url.endsWith('/shift'));
    const raw = reportFixture(); raw.cashSessionId = 99; next.flush(reportEnvelope(raw));
    expect(store.shift.report()!.cashSessionId).toBe(99);
  });
  it('cancels all reads and clears protected data on identity change', () => {
    store.shift.load(shift); const oldShift = http.expectOne(request => request.url.endsWith('/shift'));
    store.day.load(day); const oldDay = http.expectOne(request => request.url.endsWith('/day'));
    identity.generation.update(value => value + 1); identity.changed.next();
    expect(oldShift.cancelled).toBeTrue(); expect(oldDay.cancelled).toBeTrue();
    expect(store.shift.report()).toBeNull(); expect(store.day.report()).toBeNull(); expect(store.shift.loading()).toBeFalse();
  });
  it('guards late success and failure by generation even without a cancellation event', () => {
    store.shift.load(shift); const late = http.expectOne(request => request.url.endsWith('/shift'));
    store.day.load(day); const error = http.expectOne(request => request.url.endsWith('/day'));
    identity.generation.update(value => value + 1);
    late.flush(reportEnvelope(reportFixture())); error.flush(null, { status: 503, statusText: 'Unavailable' });
    expect(store.shift.report()).toBeNull(); expect(store.day.error()).toBeNull();
  });
  it('keeps the successful turn when the day fails, including on refresh', () => {
    store.shift.load(shift); http.expectOne(request => request.url.endsWith('/shift')).flush(reportEnvelope(reportFixture()));
    store.day.load(day); http.expectOne(request => request.url.endsWith('/day')).flush(null, { status: 404, statusText: 'Not found' });
    expect(store.shift.report()).not.toBeNull(); expect(store.day.error()).toBe(ReportFailure.NotVisible);
    store.day.load(day); expect(store.day.error()).toBeNull();
    http.expectOne(request => request.url.endsWith('/day')).flush(null, { status: 503, statusText: 'Unavailable' });
    expect(store.shift.report()).not.toBeNull(); expect(store.day.error()).toBe(ReportFailure.Unavailable);
  });
  it('denies CASHIER and unknown roles without issuing a query or enabling retries', () => {
    for (const role of [StaffRole.Cashier, StaffRole.Unknown]) {
      authenticateTestSession(identity, role); store.shift.load(shift); store.day.load(day);
      expect(store.shift.error()).toBe(ReportFailure.Forbidden); expect(store.day.error()).toBe(ReportFailure.Forbidden);
    }
    http.expectNone(request => request.url.startsWith(ACCOUNTING_API_BASE));
  });
});
