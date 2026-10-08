import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {DurableSalesComponent} from './durable-sales.component';
import {DurableSalesStore} from '../../core/services/durable-sales.store';
import {DurableSaleState,PaymentReversibility} from '../../core/domain/durable-sale';
import {PaymentMethod,PaymentStatus} from '../../core/domain/pos-types';
import {PaymentCoverage} from '../../core/domain/ticket-transition';

describe('existing sale refund safety copy',()=>{
  it('does not offer another capture reversal when its reversibility is Unknown',()=>{
    const identity={clientInstanceId:'client',deviceId:'device',saleId:'sale',operationId:'operation'};
    const store={loadingList:signal(false),items:signal([]),nextCursor:signal(null),notice:signal(''),busy:signal(false),
      commands:{runtime:{notice:signal('Comprobando evidencia'),refresh:()=>{}},unresolved:signal(null)},sales:{unresolved:signal(null)},
      detail:signal({identity,status:DurableSaleState.PaymentCaptured,coverage:PaymentCoverage.Partial,total:null,pending:null,cashSessionId:2,cashierId:7,createdBy:7,receipt:'receipt',reservationRef:'reservation',pendingCommand:null,lines:[],valid:true,
        payments:[{paymentId:42,status:PaymentStatus.Captured,method:PaymentMethod.Card,amount:null,fee:null,originalPaymentId:null,reversibility:PaymentReversibility.Unknown}]}),
      can:()=>false,list:jasmine.createSpy(),execute:jasmine.createSpy()};
    TestBed.configureTestingModule({imports:[DurableSalesComponent],providers:[provideRouter([]),{provide:DurableSalesStore,useValue:store}]});
    const fixture=TestBed.createComponent(DurableSalesComponent);fixture.detectChanges();
    const screen=fixture.nativeElement as HTMLElement;
    expect(screen.textContent).toContain(PaymentReversibility.Unknown.label);
    expect(screen.textContent).toContain('las nuevas reversas requieren evidencia por pago del backend');
    expect(Array.from(screen.querySelectorAll('button')).some(button=>button.textContent?.includes('Reversar pago'))).toBeFalse();
    expect(store.execute).not.toHaveBeenCalled();
  });
});
