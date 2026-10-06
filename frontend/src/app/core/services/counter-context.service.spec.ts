import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE } from '../api';
import { CounterContextService } from './counter-context.service';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';

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
