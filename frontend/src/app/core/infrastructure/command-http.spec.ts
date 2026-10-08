import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController,provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CommandHttp } from './command-http';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingCommand,AccountingCommandKind } from '../domain/accounting-command';
import { SaleCommand,SaleCommandKind } from '../domain/sale-command';
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
});
