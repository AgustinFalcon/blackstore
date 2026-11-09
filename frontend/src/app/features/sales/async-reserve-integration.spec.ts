import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CommandFailure, CommandOutcome } from '../../core/domain/accounting-command';
import { CatalogFreshness, CatalogSource } from '../../core/domain/catalog';
import { PaymentCoverage } from '../../core/domain/ticket-transition';
import { PaymentStatus, SaleStatus } from '../../core/domain/pos-types';
import { SaleAdmissionOutcome } from '../../core/domain/sale-command';
import { CommandHttp } from '../../core/infrastructure/command-http';
import { AccountingCommandsStore } from '../../core/services/accounting-commands.store';
import { CounterContextService } from '../../core/services/counter-context.service';
import { PersistenceReadiness } from '../../core/domain/data-provenance';
import { SaleCommandsStore } from '../../core/services/sale-commands.store';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { SaleTicketComponent } from './sale-ticket.component';

describe('Explicit reserve and payment integration', () => {
  const context = { clientInstanceId: '11111111-1111-1111-1111-111111111111', deviceId: 'verified-device', terminalId: 10 };
  const envelope = (data: unknown) => ({ code: 200, errorCode: null, message: null, retryable: null, traceId: 'test', data });
  const detail = (identity: any, pending: string, coverage: PaymentCoverage, history: boolean) => envelope({
    ...identity, status: pending === '0.00' ? SaleStatus.PaymentCaptured.wire : SaleStatus.Reserved.wire,
    totalAmount: '27.50', pendingAmount: pending, paymentCoverage: coverage.wire, hasPaymentHistory: history,
    evidenceValid: true, blocked: false, retired: false, receipt: 'receipt', reservationRef: 'reservation',
  });

  it('reserve produces zero captures; capture happens only after an explicit gesture and refresh', () => {
    const runtime = { rejected: signal([]), canWrite: signal(true), context: signal(context), notice: signal('Comprobado'), refresh: () => undefined };
    const sales = { runtime, unresolved: signal(null), execute: jasmine.createSpy().and.returnValue(of({ outcome: SaleAdmissionOutcome.Accepted, receipt: {} })), consult: jasmine.createSpy() };
    const accounting = { runtime, unresolved: signal(null), blocked: signal({ blocksWrites: false, label: '' }),
      execute: jasmine.createSpy().and.returnValue(of({ outcome: CommandOutcome.Committed, paymentId: 41, failure: CommandFailure.None })), consult: jasmine.createSpy() };
    let identity: any;
    const commandHttp = { ticket: jasmine.createSpy().and.callFake((next: any) => {
      identity = next;
      return of(detail(next, accounting.execute.calls.count() ? '0.00' : '27.50', accounting.execute.calls.count() ? PaymentCoverage.Paid : PaymentCoverage.Unpaid, accounting.execute.calls.count() > 0));
    }) };
    TestBed.configureTestingModule({ imports: [SaleTicketComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: AccountingCommandsStore, useValue: accounting }, { provide: SaleCommandsStore, useValue: sales },
      { provide: CommandHttp, useValue: commandHttp },
      { provide: CounterContextService, useValue: { load: () => undefined, loading: signal(false),
        readiness: signal(PersistenceReadiness.Durable),
        catalog: signal({ version: 'v1', source: CatalogSource.StoreCoreLive, freshness: CatalogFreshness.Current, importedAt: null, validUntil: null, canonicalPath: null, items: [
          { sku: 'A', name: 'Taladro', variantId: 'variant-a', priceVersion: 'P1', unitPrice: 10 },
          { sku: 'B', name: 'Amoladora', variantId: 'variant-b', priceVersion: 'P1', unitPrice: 17.5 },
        ] }), openSession: signal({ id: 2, terminalId: 10 }), blockReason: () => null } },
    ] });
    authenticateTestSession(TestBed.inject(SessionStore));
    const component = TestBed.createComponent(SaleTicketComponent).componentInstance;
    component.selectedSku = 'A'; component.addSelected(); component.selectedSku = 'B'; component.addSelected();
    component.reserve();
    expect(sales.execute).toHaveBeenCalledTimes(1);
    expect(accounting.execute).not.toHaveBeenCalled();
    expect(component.snapshot()?.pending?.decimal).toBe('27.50');
    component.paymentAmount = '27.50';
    component.capture();
    expect(accounting.execute).toHaveBeenCalledTimes(1);
    expect(identity.operationId).toBe(component.operationRef());
    expect(component.snapshot()?.coverage).toBe(PaymentCoverage.Paid);
    expect(component.paymentId()).toBe(41);
  });
});
