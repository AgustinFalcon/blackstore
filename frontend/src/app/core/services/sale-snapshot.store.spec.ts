import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Component, inject } from '@angular/core';
import { API_BASE } from '../api';
import { SaleSnapshotState } from '../domain/sale-snapshot-state';
import { authenticateTestSession } from './session-test-helper';
import { SessionStore } from './session.store';
import { SaleSnapshotStore } from './sale-snapshot.store';

describe('SaleSnapshotStore', () => {
  const saleId = 'Sale-Opaque-Aa-01';
  const firstOperation = '00000000-0000-4000-8000-000000000002';
  const secondOperation = '00000000-0000-4000-8000-000000000003';
  let store: SaleSnapshotStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), SaleSnapshotStore] });
    authenticateTestSession(TestBed.inject(SessionStore));
    store = TestBed.inject(SaleSnapshotStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('resolves one sale id across pages and then reads its operation without writes', () => {
    store.load(saleId);
    http.expectOne(request => request.url === `${API_BASE}/sales` && request.params.get('limit') === '100' && !request.params.has('cursor'))
      .flush(envelope({ items: [], nextCursor: '10' }));
    http.expectOne(request => request.url === `${API_BASE}/sales` && request.params.get('cursor') === '10')
      .flush(envelope({ items: [summary(firstOperation)], nextCursor: null }));
    http.expectOne(`${API_BASE}/sales/operations/${firstOperation}`).flush(envelope(detail(firstOperation)));

    expect(store.state()).toBe(SaleSnapshotState.Found);
    expect(store.detail()?.identity.saleId).toBe(saleId);
    http.expectNone(request => request.method !== 'GET');
  });

  it('fails closed when the same sale id has multiple operation identities', () => {
    store.load(saleId);
    http.expectOne(request => request.url === `${API_BASE}/sales`).flush(envelope({
      items: [summary(firstOperation), summary(secondOperation)], nextCursor: null,
    }));
    expect(store.state()).toBe(SaleSnapshotState.Ambiguous);
    expect(store.detail()).toBeNull();
    http.expectNone(request => request.url.includes('/operations/'));
  });

  it('rejects an invalid route identifier before issuing HTTP', () => {
    store.load('x'.repeat(81));
    expect(store.state()).toBe(SaleSnapshotState.Invalid);
    expect(store.detail()).toBeNull();
    http.expectNone(() => true);
  });

  it('treats sale identity as case-sensitive', () => {
    store.load(saleId);
    http.expectOne(request => request.url === `${API_BASE}/sales`).flush(envelope({
      items: [summary(firstOperation, saleId.toLowerCase())], nextCursor: null,
    }));
    expect(store.state()).toBe(SaleSnapshotState.NotFound);
    http.expectNone(request => request.url.includes('/operations/'));
  });

  it('fails closed for empty or non-contractual next cursors', () => {
    for (const nextCursor of ['', '0', '01', '-1', '9223372036854775808']) {
      store.load(saleId);
      http.expectOne(request => request.url === `${API_BASE}/sales`).flush(envelope({ items: [], nextCursor }));
      expect(store.state()).toBe(SaleSnapshotState.Unavailable);
    }
  });

  it('cancels the lookup when a newer route replaces it', () => {
    store.load(saleId);
    const stale = http.expectOne(request => request.url === `${API_BASE}/sales`);
    store.load('Sale-Opaque-Bb-02');
    expect(stale.cancelled).toBeTrue();
    http.expectOne(request => request.url === `${API_BASE}/sales`).flush(envelope({ items: [], nextCursor: null }));
    expect(store.state()).toBe(SaleSnapshotState.NotFound);
  });

  it('cancels the lookup when its component scope is destroyed', () => {
    const fixture = TestBed.createComponent(SnapshotStoreHost);
    fixture.componentInstance.store.load(saleId);
    const pending = http.expectOne(request => request.url === `${API_BASE}/sales`);
    fixture.destroy();
    expect(pending.cancelled).toBeTrue();
  });

  function summary(operationId: string, identitySaleId = saleId) {
    return { operationId, clientInstanceId: '00000000-0000-4000-8000-000000000004', deviceId: 'counter-1', saleId: identitySaleId,
      cashSessionId: 5, cashierId: 7, createdBy: 7, status: 'RESERVED', totalAmount: 10 };
  }
  function detail(operationId: string) {
    return { ...summary(operationId), evidenceValid: true, pendingAmount: 10, paymentCoverage: 'UNPAID', hasPaymentHistory: false,
      blocked: false, retired: false, receipt: 'receipt-1', reservationRef: 'reservation-1', pendingCommand: null,
      lines: [{ variantId: 'variant-1', sku: 'SKU-1', productName: 'Artículo', quantity: 1, originalUnitPrice: 10, priceVersion: 'v1', totalAmount: 10 }],
      payments: [], allowedActions: [] };
  }
  function envelope<T>(data: T) {
    return { code: 200, traceId: 'test-trace', data, message: null, errorCode: null, retryable: null };
  }
});

@Component({ standalone: true, template: '', providers: [SaleSnapshotStore] })
class SnapshotStoreHost { readonly store = inject(SaleSnapshotStore); }
