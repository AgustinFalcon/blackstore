import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController,provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of,Subject } from 'rxjs';
import { SaleTicketComponent } from './sale-ticket.component';
import { CounterContextService } from '../../core/services/counter-context.service';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { CommandRuntimeStore } from '../../core/services/command-runtime.store';
import { CommandHttp } from '../../core/infrastructure/command-http';
import { SaleCommand,SaleCommandKind,SaleAdmissionOutcome } from '../../core/domain/sale-command';
import { SaleAction,SaleStatus } from '../../core/domain/pos-types';
import { PaymentCoverage } from '../../core/domain/ticket-transition';
describe('SaleTicketComponent v2 guarded journey',()=>{
  let component:SaleTicketComponent;let runtime:any;let api:any;let http:HttpTestingController;
  const context={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'provisioned-device',terminalId:10};
  beforeEach(()=>{
    runtime={ pending:signal(null),canWrite:signal(true),context:signal(context),notice:signal('Comprobado'),
      execute:jasmine.createSpy().and.returnValue(Promise.resolve({outcome:SaleAdmissionOutcome.Unknown,receipt:null})),consult:jasmine.createSpy().and.returnValue(Promise.resolve(null)) };
    api={ticket:jasmine.createSpy().and.returnValue(of(null))};
    TestBed.configureTestingModule({imports:[SaleTicketComponent],providers:[provideHttpClient(),provideHttpClientTesting(),
      {provide:CommandRuntimeStore,useValue:runtime},{provide:CommandHttp,useValue:api},
      {provide:CounterContextService,useValue:{load:()=>{},catalog:signal(null),openSession:signal({id:2,terminalId:10}),blockReason:()=>null}}]});
    authenticateTestSession(TestBed.inject(SessionStore));
    component=TestBed.createComponent(SaleTicketComponent).componentInstance;http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>http.verify());
  for(const notice of ['Comprobando evidencia','Contabilidad pausada','Contexto desconocido','Storage no confirmado','Evidencia en cuarentena']){
    it('blocks writes and displays '+notice,()=>{
      runtime.canWrite.set(false);runtime.notice.set(notice);component.reserve();
      expect(component.blockReason()).toBe(notice);expect(runtime.execute).not.toHaveBeenCalled();http.expectNone(r=>r.method==='POST');
    });
  }
  it('freezes provisioned identity and canonical fields in a Reserve command without fee',async()=>{
    component.operationReason='  caja ajena  ';component.reserve();await Promise.resolve();
    const command=runtime.execute.calls.mostRecent().args[0] as SaleCommand;
    expect(command.kind).toBe(SaleCommandKind.Reserve);expect(command.identity.clientInstanceId).toBe(context.clientInstanceId);expect(command.identity.deviceId).toBe(context.deviceId);
    expect(command.body['cashSessionId']).toBe(2);expect(command.body['reason']).toBe('caja ajena');expect(command.body['feeAmount']).toBeUndefined();
    expect(Object.isFrozen(command.body)).toBeTrue();http.expectNone(r=>r.method==='POST');
  });
  it('a pending admission never authorizes capture or commit and double submit retains one command',async()=>{
    component.reserve();component.reserve();await Promise.resolve();await Promise.resolve();
    expect(runtime.execute.calls.count()).toBe(1);expect(component.canFinish(SaleAction.Commit)).toBeFalse();expect(api.ticket).not.toHaveBeenCalled();http.expectNone(r=>r.method==='POST');
  });
  it('requires matching cash terminal before intent creation',()=>{
    TestBed.inject(CounterContextService).openSession.set({id:2,terminalId:11,cashierId:7,status:undefined as any,openingCash:0});
    component.reserve();expect(runtime.execute).not.toHaveBeenCalled();expect(component.message()).toContain('no coinciden');
  });
  it('accepted admission followed by an invalid GET cannot capture',async()=>{
    runtime.execute.and.returnValue(Promise.resolve({outcome:SaleAdmissionOutcome.Accepted,receipt:{}}));
    component.reserve();await Promise.resolve();await Promise.resolve();
    expect(api.ticket.calls.count()).toBe(1);expect(runtime.execute.calls.count()).toBe(1);expect(component.canFinish(SaleAction.Commit)).toBeFalse();
  });
  it('actor change masks visible attempt and ignores late accepted response',async()=>{
    let deliver!:(value:any)=>void;runtime.execute.and.returnValue(new Promise(resolve=>deliver=resolve));
    component.reserve();TestBed.inject(SessionStore).changed.next();deliver({outcome:SaleAdmissionOutcome.Accepted,receipt:{}});
    await Promise.resolve();await Promise.resolve();expect(component.operationRef()).toBeNull();expect(component.paymentId()).toBeNull();expect(api.ticket).not.toHaveBeenCalled();
  });
});
