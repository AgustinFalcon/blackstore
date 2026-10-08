import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController,provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CommandHttp } from './command-http';
import { API_BASE,ACCOUNTING_API_BASE } from '../api';
import {AllowedAction,DurableSaleState} from '../domain/durable-sale';
import {PaymentStatus,PaymentMethod} from '../domain/pos-types';
import {PaymentCoverage} from '../domain/ticket-transition';
import { AccountingCommand,AccountingCommandKind,ExpenseOperation } from '../domain/accounting-command';
import {firstValueFrom} from 'rxjs';
import {PosWireMapper} from './pos-wire-mapper';
import {commandEnvelope} from './accounting-command-test-helper';
import { SaleCommand,SaleCommandKind } from '../domain/sale-command';
import { expenseProjectionFixture } from './expense-projection-test-helper';
import { ExpenseProjectionState } from '../domain/expense-projection';
describe('v2 command HTTP infrastructure',()=>{
  let api:CommandHttp;let http:HttpTestingController;
  const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
  beforeEach(()=>{TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});api=TestBed.inject(CommandHttp);http=TestBed.inject(HttpTestingController);});
  afterEach(()=>http.verify());
  for(const [kind,path] of [[SaleCommandKind.Reserve,'/sales/reservations'],[SaleCommandKind.Commit,`/sales/${identity.operationId}/commit`],[SaleCommandKind.Release,`/sales/${identity.operationId}/release`]] as const){
    it('sends only v2 '+kind.wire+' and selects its own receipt family',()=>{
      const command=SaleCommand.create(kind,identity,2,{cashSessionId:2,reason:'motivo'});api.post(command).subscribe();
      const post=http.expectOne(ACCOUNTING_API_BASE+path);expect(post.request.method).toBe('POST');expect(post.request.body.commandId).toBe(command.commandId);expect(post.request.body.operationId).toBe(identity.operationId);post.flush(null);
      api.receipt(command).subscribe();const get=http.expectOne(`${ACCOUNTING_API_BASE}/sales/commands/${command.commandId}`);expect(get.request.method).toBe('GET');get.flush(null);http.expectNone(r=>r.url.startsWith('/api/v1')&&r.method==='POST');
    });
  }
  for(const [kind,path,aggregate] of [[AccountingCommandKind.Open,'/cash-sessions',undefined],[AccountingCommandKind.Close,'/cash-sessions/2/close',2],[AccountingCommandKind.Expense,'/expenses',undefined],[AccountingCommandKind.Capture,'/payments',undefined],[AccountingCommandKind.Reverse,'/payments/2/reversals',2]] as const){
    it('routes accounting '+kind.wire+' through v2',()=>{
      const command=AccountingCommand.create(kind,{...identity,amount:'10'},aggregate);api.post(command).subscribe();
      const post=http.expectOne(ACCOUNTING_API_BASE+path);expect(post.request.body.commandId).toBe(command.commandId);expect(post.request.body.feeAmount).toBeUndefined();post.flush(null);
      api.receipt(command).subscribe();http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${command.commandId}`).flush(null);
    });
  }
  it('queries context and lifecycle independently with GET only',()=>{
    api.context().subscribe();api.lifecycle().subscribe();
    for(const path of ['/pos/context','/accounting/runtime']){const read=http.expectOne(ACCOUNTING_API_BASE+path);expect(read.request.method).toBe('GET');read.flush(null);}
    http.expectNone(r=>r.method==='POST');
  });
  for(const operation of [ExpenseOperation.Accrue,ExpenseOperation.AccrueAndSettle,ExpenseOperation.SettleExisting]){
    it('resolves '+operation.wire+' only by command scoped GET with exact receipt facts',async()=>{
      const command=AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,operation:operation.wire,amount:'10',category:'supplies',expenseId:5,paymentMethod:PaymentMethod.Cash.wire});
      const projection=expenseProjectionFixture(command),p=projection.data.projection;
      const receipt=PosWireMapper.commandReceipt(commandEnvelope(command.commandId,{expenseId:5,settlementId:p.settlementId,ledgerEventIds:p.ledgerEventIds}),command.commandId);
      const result=firstValueFrom(api.refresh(command,receipt,()=>true,7));
      const get=http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${command.commandId}/projection`);expect(get.request.method).toBe('GET');get.flush(projection);
      expect(await result).toBeTrue();http.expectNone(r=>r.method==='POST');
    });
    it('cannot resolve '+operation.wire+' from an open cash session and receipt ids',async()=>{
      const command=AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,operation:operation.wire,amount:'10',category:'supplies',expenseId:5});
      const receipt=PosWireMapper.commandReceipt(commandEnvelope(command.commandId,{expenseId:5,settlementId:6}),command.commandId);
      const result=firstValueFrom(api.refresh(command,receipt,()=>true,7));
      const get=http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${command.commandId}/projection`);expect(get.request.method).toBe('GET');
      get.flush({code:404,traceId:'test',data:{state:'NOT_FOUND',projection:null}},{status:404,statusText:'Not Found'});
      expect(await result).toBeFalse();http.expectNone(r=>r.method==='POST');
    });
  }
  for(const [state,code] of [[ExpenseProjectionState.NotFound,404],[ExpenseProjectionState.Unavailable,503],[ExpenseProjectionState.Unknown,503]] as const){
    it('keeps '+state.wire+' recovery GET-only',async()=>{
      const command=AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,operation:ExpenseOperation.Accrue.wire,amount:'10',category:'supplies'});
      const receipt=PosWireMapper.commandReceipt(commandEnvelope(command.commandId,{expenseId:5}),command.commandId);
      const result=firstValueFrom(api.refresh(command,receipt,()=>true,7));
      http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${command.commandId}/projection`).flush({code,traceId:'test',data:{state:state.wire,projection:null}},{status:code,statusText:'Unavailable'});
      expect(await result).toBeFalse();http.expectNone(r=>r.method==='POST');
    });
  }
  it('discards a Found projection arriving after the generation/context guard expired',async()=>{
    const command=AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,operation:ExpenseOperation.Accrue.wire,amount:'10',category:'supplies'}),raw=expenseProjectionFixture(command);
    const receipt=PosWireMapper.commandReceipt(commandEnvelope(command.commandId,{expenseId:5}),command.commandId);
    let current=true;const result=firstValueFrom(api.refresh(command,receipt,()=>current,7));current=false;
    http.expectOne(`${ACCOUNTING_API_BASE}/expenses/commands/${command.commandId}/projection`).flush(raw);expect(await result).toBeFalse();
  });
  it('rejects another reverse before journal when current detail contains a refund',async()=>{
    const command=AccountingCommand.create(AccountingCommandKind.Reverse,{...identity,originalPaymentId:41,reason:'refund',evidenceRef:'ref'},41);
    const result=firstValueFrom(api.references(command,10));
    http.expectOne(`${API_BASE}/sales/operations/${identity.operationId}`).flush({code:200,traceId:'test',data:{...identity,cashSessionId:2,cashierId:7,createdBy:7,status:DurableSaleState.PaymentCaptured.wire,totalAmount:'18',pendingAmount:'10',paymentCoverage:PaymentCoverage.Partial.wire,hasPaymentHistory:true,evidenceValid:true,blocked:false,retired:false,receipt:'receipt',reservationRef:'reservation',pendingCommand:null,allowedActions:[AllowedAction.ReversePayment.wire],
      lines:[{sku:'SKU',productName:'Product',quantity:1,totalAmount:'18'}],payments:[
        {paymentId:41,status:PaymentStatus.Captured.wire,method:PaymentMethod.Cash.wire,amount:'10',feeAmount:'0'},
        {paymentId:42,status:PaymentStatus.Captured.wire,method:PaymentMethod.Card.wire,amount:'8',feeAmount:'0'},
        {paymentId:43,status:PaymentStatus.Refunded.wire,method:PaymentMethod.Cash.wire,amount:'10',feeAmount:'0'}]}});
    expect(await result).toBeFalse();http.expectNone(r=>r.url.includes('cash-sessions')||r.method==='POST');
  });
});
