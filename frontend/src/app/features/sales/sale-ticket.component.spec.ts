import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AccountingCommand } from '../../core/domain/accounting-command';
import { ReversalReason } from '../../core/domain/reversal-reason';
import { SaleAdmissionOutcome, SaleAdmissionRoute, SaleCommand } from '../../core/domain/sale-command';
import { CommandHttp } from '../../core/infrastructure/command-http';
import { CommandRuntimeStore } from '../../core/services/command-runtime.store';
import { CounterContextService } from '../../core/services/counter-context.service';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { SaleTicketComponent } from './sale-ticket.component';

describe('SaleTicketComponent explicit cart journey', () => {
  let component: SaleTicketComponent;
  let runtime: any;
  let session: SessionStore;
  const context = { clientInstanceId: '11111111-1111-1111-1111-111111111111', deviceId: 'provisioned-device', terminalId: 10 };
  const catalog = signal({ version: 'v1', stale: false, importedAt: null, validUntil: null, items: [
    { sku: 'SKU-A', name: 'Taladro profesional', variantId: 'variant-a', priceVersion: 'P1', unitPrice: 10 },
    { sku: 'SKU-B', name: 'Amoladora compacta', variantId: 'variant-b', priceVersion: 'P2', unitPrice: 17.5 },
  ] });

  beforeEach(() => {
    runtime = { pending: signal(null), canWrite: signal(true), context: signal(context), notice: signal('Comprobado'),
      execute: jasmine.createSpy().and.returnValue(Promise.resolve({ outcome: SaleAdmissionOutcome.Unknown, receipt: null })),
      consult: jasmine.createSpy().and.returnValue(Promise.resolve(null)) };
    TestBed.configureTestingModule({ imports: [SaleTicketComponent], providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: CommandRuntimeStore, useValue: runtime },
      { provide: CommandHttp, useValue: { ticket: jasmine.createSpy().and.returnValue(of(null)) } },
      { provide: CounterContextService, useValue: { load: () => undefined, loading: signal(false), catalog,
        openSession: signal({ id: 2, terminalId: 10 }), blockReason: () => null } },
    ] });
    session = TestBed.inject(SessionStore);
    authenticateTestSession(session);
    component = TestBed.createComponent(SaleTicketComponent).componentInstance;
    component.selectedSku = 'SKU-A';
  });

  it('builds a multiline cart from authorized prices and merges a repeated variant', () => {
    component.addSelected(); component.addSelected();
    component.selectedSku = 'SKU-B'; component.addSelected();
    expect(component.cart.lineCount()).toBe(2);
    expect(component.cart.unitCount()).toBe(3);
    expect(component.cart.draft().lines[0].quantity).toBe(2);
    expect(component.cart.draft().total?.decimal).toBe('37.50');
  });

  it('creates one V3 multiline reserve and never schedules an automatic payment', async () => {
    component.addSelected(); component.selectedSku = 'SKU-B'; component.addSelected();
    spyOn(component, 'blockReason').and.returnValue(null);
    expect(component.canReserve()).toBeTrue();
    component.reserve(); component.reserve();
    await Promise.resolve(); await Promise.resolve();
    expect(runtime.execute.calls.count()).toBe(1);
    const command = runtime.execute.calls.mostRecent().args[0] as SaleCommand;
    expect(command).toEqual(jasmine.any(SaleCommand));
    expect(command.route).toBe(SaleAdmissionRoute.ReserveV3);
    expect((command.body['lines'] as readonly unknown[]).length).toBe(2);
    expect(runtime.execute.calls.allArgs().flat().some((value: unknown) => value instanceof AccountingCommand)).toBeFalse();
  });

  it('renders separate reserve and payment language with an accessible empty state', async () => {
    const fixture = TestBed.createComponent(SaleTicketComponent);
    fixture.detectChanges(); await fixture.whenStable();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('El carrito está vacío');
    expect(text).toContain('Armá el carrito, reservá el stock y registrá cada cobro de forma explícita');
    expect(text).not.toContain('Reservar y cobrar');
    expect(fixture.nativeElement.querySelector('[aria-live="polite"]')).not.toBeNull();
  });

  it('rejects invalid reversal reasons at the UI boundary', () => {
    for (const reason of ['', 'x', 'ok', 'x'.repeat(ReversalReason.maxLength + 1), '   ']) {
      component.reversalReason = reason;
      expect(component.validReversalReason()).toBeFalse();
    }
    component.reversalReason = '  cobro duplicado  ';
    expect(component.validReversalReason()).toBeTrue();
  });

  it('clears draft and evidence when the authenticated actor changes', () => {
    component.addSelected(); session.changed.next();
    expect(component.cart.lineCount()).toBe(0);
    expect(component.operationRef()).toBeNull();
  });
});
