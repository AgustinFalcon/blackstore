import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { DurableSaleState } from '../../core/domain/durable-sale';
import { PaymentCoverage, TicketMoney } from '../../core/domain/ticket-transition';
import { SaleSnapshotState } from '../../core/domain/sale-snapshot-state';
import { SaleSnapshotStore } from '../../core/services/sale-snapshot.store';
import { SaleTicketSnapshotComponent } from './sale-ticket-snapshot.component';
import { RecordedInstant } from '../../core/domain/recorded-instant';

describe('SaleTicketSnapshotComponent', () => {
  it('loads the routed sale and exposes only read-only ticket evidence', () => {
    const saleId = 'Sale-Opaque-Aa-01';
    const store = {
      state: signal(SaleSnapshotState.Found),
      detail: signal({
        identity: { clientInstanceId: 'client', deviceId: 'device', saleId, operationId: 'operation' },
        cashSessionId: 9, cashierId: 12, createdBy: 12, status: DurableSaleState.Committed,
        createdAt: RecordedInstant.fromWire('2026-11-12T14:30:00Z'), updatedAt: RecordedInstant.fromWire('2026-11-12T15:45:00Z'),
        total: TicketMoney.fromDecimal('1234.50'), pending: null, coverage: PaymentCoverage.Paid, hasPaymentHistory: false,
        lines: [], payments: [], allowedActions: [], valid: true, receipt: 'receipt-1',
        reservationRef: 'reservation-1', pendingCommand: null,
      }),
      loadPathSegment: jasmine.createSpy(), retry: jasmine.createSpy(),
    };
    TestBed.configureTestingModule({
      imports: [SaleTicketSnapshotComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ saleId: `id:${saleId}` })) } },
      ],
    });
    TestBed.overrideComponent(SaleTicketSnapshotComponent, {
      set: { providers: [{ provide: SaleSnapshotStore, useValue: store }] },
    });

    const fixture = TestBed.createComponent(SaleTicketSnapshotComponent);
    fixture.detectChanges();
    const screen = fixture.nativeElement as HTMLElement;

    expect(store.loadPathSegment).toHaveBeenCalledOnceWith(`id:${saleId}`);
    expect(screen.textContent).toContain(saleId);
    expect(screen.textContent).toContain('Comprobante operativo de sólo lectura');
    expect(screen.textContent).toContain('ARS');
    expect(screen.textContent).toContain('1.234,50');
    expect(screen.textContent).toContain('Creada');
    expect(screen.textContent).toContain('Última actualización');
    expect(screen.textContent).not.toContain('FechaNo disponible');
    expect(screen.querySelector('[data-source="UNKNOWN"]')?.textContent).toContain('Procedencia desconocida');
    expect(Array.from(screen.querySelectorAll('details')).some(item => item.textContent?.includes('receipt-1'))).toBeTrue();
    expect(screen.textContent).toContain('Esta ruta no ofrece acciones mutantes');
    expect(Array.from(screen.querySelectorAll('button')).some(button =>
      /confirmar|reversar|liberar|cobrar/i.test(button.textContent ?? ''))).toBeFalse();
  });
});
