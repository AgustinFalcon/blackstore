import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AccountingCommand } from '../../core/domain/accounting-command';
import { CartPhase } from '../../core/domain/cart';
import { DurableLineMetadata, DurableSaleState } from '../../core/domain/durable-sale';
import { PaymentCoverage, PaymentLedgerSemantics, TicketMoney } from '../../core/domain/ticket-transition';
import { SaleStatus } from '../../core/domain/pos-types';
import { ReversalReason } from '../../core/domain/reversal-reason';
import { SaleAdmissionOutcome, SaleAdmissionRoute, SaleCommand } from '../../core/domain/sale-command';
import { CommandHttp } from '../../core/infrastructure/command-http';
import { CommandRuntimeStore } from '../../core/services/command-runtime.store';
import { CounterContextService } from '../../core/services/counter-context.service';
import { DurableSalesStore } from '../../core/services/durable-sales.store';
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
      provideRouter([]),
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

  it('restores an invalid visible quantity and marks its field invalid',()=>{
    const fixture=TestBed.createComponent(SaleTicketComponent),tested=fixture.componentInstance;
    tested.selectedSku='SKU-A';tested.addSelected();fixture.detectChanges();
    const input=fixture.nativeElement.querySelector('.quantity input') as HTMLInputElement;
    input.value='0';input.dispatchEvent(new Event('change'));fixture.detectChanges();
    expect(input.value).toBe('1');expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(tested.cart.draft().lines[0].quantity).toBe(1);
  });

  it('associates concrete payment validation errors before submit',()=>{
    component.cart.moveTo(CartPhase.AwaitingPayment);
    component.snapshot.set(Object.freeze({identity:{clientInstanceId:'c',deviceId:'d',saleId:'s',operationId:'o'},status:SaleStatus.Reserved,
      evidenceValid:true,receipt:'r',reservationRef:'x',total:TicketMoney.fromDecimal('10'),pending:TicketMoney.fromDecimal('10'),
      coverage:PaymentCoverage.Unpaid,hasPaymentHistory:false,blocked:false,retired:false,ledgerSemantics:PaymentLedgerSemantics.Net}));
    component.paymentAmount='10.001';expect(component.paymentValidationMessage()).toContain('dos decimales');
    component.paymentAmount='11';expect(component.paymentValidationMessage()).toContain('no puede superar');
    component.paymentAmount='10';expect(component.paymentValidationMessage()).toBeNull();
  });

  it('never upgrades an invalid durable detail into a capturable lightweight snapshot',()=>{
    const money=TicketMoney.fromDecimal('10.00')!;
    (component as any).openDurable({identity:{clientInstanceId:'c',deviceId:'d',saleId:'s',operationId:'o'},cashSessionId:2,
      cashierId:7,createdBy:7,status:DurableSaleState.Reserved,total:money,pending:money,coverage:PaymentCoverage.Unpaid,
      hasPaymentHistory:false,lines:[{metadata:DurableLineMetadata.Complete,variantId:'v',sku:'SKU',productName:'Producto',quantity:1,
        originalUnitPrice:money,priceVersion:'P1',total:money}],payments:[],allowedActions:[],valid:false,receipt:'r',reservationRef:'x',pendingCommand:null});
    expect(component.snapshot()?.evidenceValid).toBeFalse();expect(component.phase()).toBe(CartPhase.Unknown);
    component.paymentAmount='10';expect(component.canCapture()).toBeFalse();
  });

  it('adopts durable detail through the effect without tracking its internal cart writes',async()=>{
    const fixture=TestBed.createComponent(SaleTicketComponent),tested=fixture.componentInstance,money=TicketMoney.fromDecimal('10.00')!;
    fixture.detectChanges();await fixture.whenStable();
    tested.requestedOperationId.set('o');TestBed.inject(DurableSalesStore).detail.set({identity:{clientInstanceId:'c',deviceId:'d',saleId:'s',operationId:'o'},
      cashSessionId:2,cashierId:7,createdBy:7,status:DurableSaleState.Reserved,total:money,pending:money,coverage:PaymentCoverage.Unpaid,
      hasPaymentHistory:false,lines:[{metadata:DurableLineMetadata.Complete,variantId:'v',sku:'SKU',productName:'Producto',quantity:1,
        originalUnitPrice:money,priceVersion:'P1',total:money}],payments:[],allowedActions:[],valid:true,receipt:'r',reservationRef:'x',pendingCommand:null});
    fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(tested.phase()).toBe(CartPhase.AwaitingPayment);expect(tested.cart.lineCount()).toBe(1);
    tested.cart.moveTo(CartPhase.Completed);tested.message.set('Pago comprobado');fixture.detectChanges();await fixture.whenStable();
    expect(tested.phase()).toBe(CartPhase.Completed);
  });
});
