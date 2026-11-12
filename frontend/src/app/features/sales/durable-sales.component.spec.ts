import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {DurableSalesComponent} from './durable-sales.component';
import {DurableSalesStore} from '../../core/services/durable-sales.store';
import {AllowedAction,DurableSaleState,PaymentReversibility} from '../../core/domain/durable-sale';
import {PaymentMethod,PaymentStatus} from '../../core/domain/pos-types';
import {PaymentCoverage,TicketMoney} from '../../core/domain/ticket-transition';
import {ReversalReason} from '../../core/domain/reversal-reason';
import {SurfaceDataSource} from '../../core/domain/data-provenance';
import {OperationId,SaleSelectionPhase} from '../../core/domain/sale-operation-selection';
import {ActivatedRoute,Router,convertToParamMap} from '@angular/router';
import {BehaviorSubject} from 'rxjs';
import {RecordedInstant} from '../../core/domain/recorded-instant';

describe('existing sale refund safety copy',()=>{
  it('does not offer another capture reversal when its reversibility is Unknown',()=>{
    const identity={clientInstanceId:'client',deviceId:'device',saleId:'sale',operationId:'operation'};
    const store={loadingList:signal(false),items:signal([]),nextCursor:signal(null),notice:signal(''),busy:signal(false),
      listSource:signal(SurfaceDataSource.Unknown),detailSource:signal(SurfaceDataSource.Unknown),
      selectedOperationId:signal(OperationId.fromWire(identity.operationId)),selectionPhase:signal(SaleSelectionPhase.Ready),
      commands:{runtime:{notice:signal('Comprobando evidencia'),refresh:()=>{}},unresolved:signal(null)},sales:{unresolved:signal(null)},
      detail:signal({identity,status:DurableSaleState.PaymentCaptured,coverage:PaymentCoverage.Partial,total:TicketMoney.fromDecimal('18'),pending:TicketMoney.fromDecimal('8'),cashSessionId:2,cashierId:7,createdBy:7,createdAt:RecordedInstant.fromWire('2026-11-12T14:30:00Z'),updatedAt:RecordedInstant.fromWire('2026-11-12T15:45:00Z'),receipt:'receipt',reservationRef:'reservation',pendingCommand:null,lines:[],valid:true,
        payments:[{paymentId:42,status:PaymentStatus.Captured,method:PaymentMethod.Card,amount:null,fee:null,createdAt:RecordedInstant.fromWire('2026-11-12T15:00:00Z'),originalPaymentId:null,reversibility:PaymentReversibility.Unknown}]}),
      can:()=>true,list:jasmine.createSpy(),execute:jasmine.createSpy(),selectOperation:jasmine.createSpy(),retrySelection:jasmine.createSpy(),refreshSelection:jasmine.createSpy(),
      rejectSelection:jasmine.createSpy(),clearSelection:jasmine.createSpy(),leaveSelection:jasmine.createSpy(),consultReceipt:jasmine.createSpy()};
    TestBed.configureTestingModule({imports:[DurableSalesComponent],providers:[provideRouter([]),{provide:DurableSalesStore,useValue:store}]});
    const fixture=TestBed.createComponent(DurableSalesComponent);fixture.detectChanges();
    const screen=fixture.nativeElement as HTMLElement;
    expect(screen.textContent).toContain(PaymentReversibility.Unknown.label);
    expect(screen.querySelector('[data-source="UNKNOWN"]')?.textContent).toContain('Procedencia desconocida');
    expect(Array.from(screen.querySelectorAll('details')).some(item => item.textContent?.includes('receipt'))).toBeTrue();
    expect(screen.textContent).toContain('las nuevas reversas requieren evidencia por pago del backend');
    expect(Array.from(screen.querySelectorAll('button')).some(button=>button.textContent?.includes('Reversar pago'))).toBeFalse();
    expect(store.execute).not.toHaveBeenCalled();
    const guidance=screen.querySelector('#durable-reversal-reason-guidance');
    expect(guidance?.textContent).toContain(ReversalReason.guidance);
    expect(screen.querySelector('input[aria-describedby="durable-reversal-reason-guidance"]')).not.toBeNull();
    const component=fixture.componentInstance;
    expect(component.captureValidationMessage()).toContain('importe válido');
    component.amount='9';expect(component.captureValidationMessage()).toContain('no puede superar');
    component.amount='8';expect(component.captureValidationMessage()).toBeNull();component.capturePayment();
    expect(store.execute).toHaveBeenCalledWith(AllowedAction.CapturePayment,'','8',PaymentMethod.Cash);
  });
});

describe('existing sale route selection',()=>{
  const operationId='33333333-3333-3333-3333-333333333333';
  const routeParams=new BehaviorSubject(convertToParamMap({}));
  let store:any;let router:any;
  beforeEach(()=>{
    routeParams.next(convertToParamMap({}));
    store={loadingList:signal(false),items:signal([{identity:{clientInstanceId:'client',deviceId:'device',saleId:'sale',operationId},
      cashSessionId:1,cashierId:7,createdBy:7,status:DurableSaleState.Reserved,total:null,createdAt:RecordedInstant.fromWire('2026-11-12T14:30:00Z'),updatedAt:RecordedInstant.fromWire('2026-11-12T15:45:00Z')}]),nextCursor:signal(null),notice:signal(null),busy:signal(false),
      listSource:signal(SurfaceDataSource.Real),detailSource:signal(SurfaceDataSource.Unknown),detail:signal(null),
      selectedOperationId:signal(null),selectionPhase:signal(SaleSelectionPhase.Idle),
      commands:{runtime:{notice:signal(''),refresh:jasmine.createSpy()},unresolved:signal(null),blocked:signal({blocksWrites:false})},sales:{unresolved:signal(null)},
      can:()=>false,list:jasmine.createSpy(),execute:jasmine.createSpy(),selectOperation:jasmine.createSpy(),retrySelection:jasmine.createSpy(),refreshSelection:jasmine.createSpy(),
      rejectSelection:jasmine.createSpy(),clearSelection:jasmine.createSpy(),leaveSelection:jasmine.createSpy(),consultReceipt:jasmine.createSpy()};
    router={navigate:jasmine.createSpy().and.resolveTo(true)};
    TestBed.configureTestingModule({imports:[DurableSalesComponent],providers:[
      {provide:DurableSalesStore,useValue:store},{provide:ActivatedRoute,useValue:{queryParamMap:routeParams}},{provide:Router,useValue:router},
    ]});
  });
  it('exposes a fresh-list selector that only updates the URL',()=>{
    const fixture=TestBed.createComponent(DurableSalesComponent);fixture.detectChanges();
    const selector=Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(button=>button.textContent?.includes('Ver operaciones')) as HTMLButtonElement;
    expect(selector).toBeDefined();selector.click();
    expect(router.navigate).toHaveBeenCalledWith([],{relativeTo:jasmine.anything(),queryParams:{operationId},queryParamsHandling:'merge'});
    expect(store.execute).not.toHaveBeenCalled();
  });
  it('translates valid, duplicate and missing route selections once at the boundary',()=>{
    const fixture=TestBed.createComponent(DurableSalesComponent);fixture.detectChanges();
    routeParams.next(convertToParamMap({operationId}));
    expect(store.selectOperation).toHaveBeenCalledWith(jasmine.objectContaining({wire:operationId}));
    routeParams.next(convertToParamMap({operationId:[operationId,operationId]}));
    expect(store.rejectSelection).toHaveBeenCalled();
    routeParams.next(convertToParamMap({}));
    expect(store.clearSelection).toHaveBeenCalled();
  });
  it('does not present a failed list as a verified empty list',()=>{
    store.items.set([]);store.listSource.set(SurfaceDataSource.Unavailable);
    const fixture=TestBed.createComponent(DurableSalesComponent);fixture.detectChanges();
    const screen=fixture.nativeElement as HTMLElement;
    expect(screen.textContent).toContain('No pudimos comprobar el listado');
    expect(screen.textContent).not.toContain('No hay ventas visibles');
  });
  it('keeps loading cancellable and exposes a real refresh with an accessible completion status',()=>{
    store.selectedOperationId.set(OperationId.fromWire(operationId));store.selectionPhase.set(SaleSelectionPhase.Loading);
    const fixture=TestBed.createComponent(DurableSalesComponent);fixture.detectChanges();
    let screen=fixture.nativeElement as HTMLElement;
    expect(Array.from(screen.querySelectorAll('[role="status"]')).some(status=>status.textContent?.includes(SaleSelectionPhase.Loading.label))).toBeTrue();
    const close=Array.from(screen.querySelectorAll('button')).find(button=>button.textContent?.trim()==='Cerrar') as HTMLButtonElement;
    expect(close).toBeDefined();close.click();expect(store.clearSelection).toHaveBeenCalled();
    store.selectionPhase.set(SaleSelectionPhase.Ready);store.detail.set({identity:{clientInstanceId:'client',deviceId:'device',saleId:'sale',operationId},
      status:DurableSaleState.Reserved,coverage:PaymentCoverage.Partial,total:TicketMoney.fromDecimal('18'),pending:TicketMoney.fromDecimal('8'),
      cashSessionId:1,cashierId:7,createdBy:7,createdAt:RecordedInstant.fromWire('2026-11-12T14:30:00Z'),updatedAt:RecordedInstant.fromWire('2026-11-12T15:45:00Z'),receipt:null,reservationRef:null,pendingCommand:null,lines:[],payments:[],valid:true});
    fixture.detectChanges();screen=fixture.nativeElement as HTMLElement;
    expect(screen.querySelector('.selection-status[role="status"]')?.textContent).toContain(SaleSelectionPhase.Ready.label);
    const refresh=Array.from(screen.querySelectorAll('button')).find(button=>button.textContent?.includes('Consultar estado')) as HTMLButtonElement;
    refresh.click();expect(store.refreshSelection).toHaveBeenCalled();
  });
});
