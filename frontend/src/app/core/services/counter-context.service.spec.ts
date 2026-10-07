import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../api';
import { CounterContextService } from './counter-context.service';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';
import { CashSessionStatus, PersistenceMode } from '../domain/pos-types';

describe('CounterContextService', () => {
  let service: CounterContextService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(CounterContextService);
    authenticateTestSession(TestBed.inject(SessionStore));
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('invalidates the old context immediately and ignores responses from an earlier load', () => {
    service.load(); flushLoad(http, CashSessionStatus.Open.wire);
    expect(service.openSession()).not.toBeNull();
    service.load();
    const stale = http.match(request => request.method === 'GET');
    expect(service.openSession()).toBeNull(); expect(service.loading()).toBeTrue();
    service.load(); flushLoad(http, CashSessionStatus.Closed.wire);
    stale.forEach(request => request.flush(envelope(request.request.url.endsWith('/cash-sessions') ?
      [{ id: 99, terminalId: 10, cashierId: 7, status: CashSessionStatus.Open.wire, openingCash: 0 }] : null)));
    expect(service.openSession()).toBeNull(); expect(service.loading()).toBeFalse();
  });

  it('does not restore cash or catalog from requests belonging to another identity', () => {
    service.load();
    const identity = TestBed.inject(SessionStore);
    identity.generation.update(value => value + 1); identity.changed.next();
    flushLoad(http, CashSessionStatus.Open.wire);
    expect(service.openSession()).toBeNull(); expect(service.catalog()).toBeNull();
    expect(service.persistence()).toBe(PersistenceMode.Unknown);
    expect(service.loading()).toBeFalse();
  });

  it('selects an OPEN session after translating the wire response', () => {
    service.load();
    flushLoad(http, 'OPEN');

    expect(service.openSession()?.id).toBe(5);
    expect(service.openSession()?.status.isOpen).toBeTrue();
    expect(service.persistence().label).toBe('memoria local');
    expect(service.blockReason()).toBeNull();
  });

  it('does not treat an unknown cash state as an open session', () => {
    service.load();
    flushLoad(http, 'FUTURE_STATE');

    expect(service.openSession()).toBeNull();
    expect(service.blockReason()).toBe('No hay sesión de caja abierta.');
  });
});

function flushLoad(http: HttpTestingController, status: string): void {
  http.expectOne(`${API_BASE}/workspace`).flush(envelope({ terminalId: 10, cashierId: 7, persistence: 'memory' }));
  http.expectOne(`${API_BASE}/cash-sessions`).flush(envelope([
    { id: 5, terminalId: 10, cashierId: 7, status, openingCash: 100 },
  ]));
  http.expectOne(`${API_BASE}/catalog`).flush(envelope({
    version: 'catalog-v1',
    stale: false,
    importedAt: null,
    validUntil: null,
    items: [{ sku: 'SKU-1', name: 'Producto', variantId: '1', priceVersion: 'price-v1', unitPrice: 10 }],
  }));
}

function envelope<T>(data: T) {
  return { code: 200, traceId: 'test', data, message: null, errorCode: null, retryable: null };
}
