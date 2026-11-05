import { PosWireMapper } from './pos-wire-mapper';
import { PosContextState } from '../domain/pos-execution-context';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { SaleCommand, SaleCommandKind, SaleAdmissionOutcome,SaleCommandFailure,SaleFingerprintVersion,SaleLineFailureCode } from '../domain/sale-command';
import { SaleAdmissionFingerprint,SaleAdmissionV2Fingerprint } from './sale-admission-fingerprint';
const identity = {clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'logical-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
const envelope = (data:unknown,code=200)=>({code,traceId:'test',data});
describe('v2 context lifecycle and sale admission boundary',()=>{
  const context={clientInstanceId:identity.clientInstanceId,deviceId:identity.deviceId,terminalId:10};
  it('decodes Available and neutral Unavailable',()=>{
    expect(PosWireMapper.posContext(envelope({state:PosContextState.Available.wire,context})).context).toEqual(context);
    expect(PosWireMapper.posContext(envelope({state:PosContextState.Unavailable.wire,context:null},503)).state).toBe(PosContextState.Unavailable);
  });
  for(const change of [{clientInstanceId:'bad'},{deviceId:''},{deviceId:' x '},{deviceId:'x'.repeat(81)},{terminalId:0},{terminalId:'10'}]){
    it('neutralizes invalid context '+JSON.stringify(change),()=>expect(PosWireMapper.posContext(envelope({state:PosContextState.Available.wire,context:{...context,...change}})).state).toBe(PosContextState.Unknown));
  }
  it('rejects contradictory envelope, unknown state and raw unavailable context',()=>{
    for(const raw of [envelope({state:'future',context}),envelope({state:PosContextState.Available.wire,context},503),envelope({state:PosContextState.Unavailable.wire,context},503),{code:200,data:{state:PosContextState.Available.wire,context}}])
      expect(PosWireMapper.posContext(raw).context).toBeNull();
  });
  for(const state of [AccountingLifecycleState.Active,AccountingLifecycleState.Paused,AccountingLifecycleState.PreActivation]){
    it('lifecycle validates '+state.label,()=>{
      const data={state:state.wire,contractVersion:'V2',activationAt:state===AccountingLifecycleState.PreActivation?null:'2026-10-07T00:00:00Z',observedAt:'2026-10-08T00:00:00Z'};
      expect(PosWireMapper.lifecycle(envelope(data))).toBe(state);
      expect(PosWireMapper.lifecycle(envelope({...data,observedAt:'bad'}))).toBe(AccountingLifecycleState.Unknown);
      expect(PosWireMapper.lifecycle(envelope({...data,contractVersion:'future'}))).toBe(AccountingLifecycleState.Unknown);
    });
  }
  for(const kind of [SaleCommandKind.Reserve,SaleCommandKind.Commit,SaleCommandKind.Release]){
    it('correlates accepted '+kind.wire+' without commercial terminality',async()=>{
      const command=SaleCommand.create(kind,identity,2,{reason:'  motivo  ',cashSessionId:2,variantId:'variant',quantity:1,expectedPriceVersion:'v1',sku:'sku',productName:'Product',originalUnitPrice:'20',discountAmount:'2'});
      const expectedHash=await SaleAdmissionFingerprint.hash(command,7);
      const receipt={...identity,commandId:command.commandId,kind:kind.wire,actorId:7,cashSessionId:2,payloadHash:expectedHash,intentId:1,outboxId:2,acceptedAt:'2026-10-08T00:00:00Z'};
      const response=envelope({outcome:SaleAdmissionOutcome.Accepted.wire,receipt,failure:null},202);
      expect(PosWireMapper.saleAdmission(response,command,7,expectedHash).outcome).toBe(SaleAdmissionOutcome.Accepted);
      expect(command.body['reason']).toBe('motivo');expect(command.body['feeAmount']).toBeUndefined();
      for(const change of [{actorId:8},{cashSessionId:3},{commandId:crypto.randomUUID()},{operationId:crypto.randomUUID()},{kind:'future'},{payloadHash:'bad'},{payloadHash:'f'.repeat(64)},{acceptedAt:'bad'},{intentId:0},{outboxId:0}])
        expect(PosWireMapper.saleAdmission(envelope({outcome:SaleAdmissionOutcome.Accepted.wire,receipt:{...receipt,...change},failure:null},202),command,7,expectedHash).outcome).toBe(SaleAdmissionOutcome.Unknown);
    });
  }
  it('does not infer acceptance from status 202 or an absent receipt',()=>{
    const command=SaleCommand.create(SaleCommandKind.Reserve,identity,2,{});
    expect(PosWireMapper.saleAdmission(envelope({outcome:SaleAdmissionOutcome.Accepted.wire,receipt:null,failure:null},202),command,7).outcome).toBe(SaleAdmissionOutcome.Unknown);
  });
  it('requires explicit V2 evidence for v3 and accepts an opaque saleId',async()=>{
    const v3Identity={...identity,saleId:'opaque-sale'},lines=[{variantId:'a',quantity:2,expectedPriceVersion:'P1',sku:'SKU-A',productName:'Artículo A',originalUnitPrice:'10.00',discountAmount:'0.00'}];
    const command=SaleCommand.createMultilineReserve(v3Identity,2,lines),hash=await new SaleAdmissionV2Fingerprint().hash(command,7);
    const receipt={...v3Identity,commandId:command.commandId,kind:'Reserve',actorId:7,cashSessionId:2,payloadHash:hash,intentId:1,outboxId:2,
      acceptedAt:'2026-10-08T00:00:00Z',fingerprintVersion:SaleFingerprintVersion.AdmissionV2.wire};
    expect(PosWireMapper.saleAdmission(envelope({outcome:'Accepted',receipt,failure:null,lineFailures:[]},202),command,7,hash).outcome).toBe(SaleAdmissionOutcome.Accepted);
    for(const fingerprintVersion of [undefined,SaleFingerprintVersion.AdmissionV1.wire,'future'])
      expect(PosWireMapper.saleAdmission(envelope({outcome:'Accepted',receipt:{...receipt,fingerprintVersion},failure:null,lineFailures:[]},202),command,7,hash).outcome).toBe(SaleAdmissionOutcome.Unknown);
  });
  it('preserves V2 reference whitespace and rejects explicit incompatible receipt versions on V1 routes',async()=>{
    const v3Identity={...identity,deviceId:'\u00a0device\u00a0',saleId:'\u00a0sale\u00a0'};
    const reserve=SaleCommand.createMultilineReserve(v3Identity,2,[{variantId:'a',quantity:1,expectedPriceVersion:'P1',sku:'SKU',productName:'Artículo',originalUnitPrice:'1',discountAmount:'0'}]);
    const reserveHash=await new SaleAdmissionV2Fingerprint().hash(reserve,7);
    const reserveReceipt={...v3Identity,commandId:reserve.commandId,kind:'Reserve',actorId:7,cashSessionId:2,payloadHash:reserveHash,intentId:1,outboxId:2,
      acceptedAt:'2026-10-08T00:00:00Z',fingerprintVersion:SaleFingerprintVersion.AdmissionV2.wire};
    expect(PosWireMapper.saleAdmission(envelope({outcome:'Accepted',receipt:reserveReceipt,failure:null,lineFailures:[]},202),reserve,7,reserveHash).outcome).toBe(SaleAdmissionOutcome.Accepted);

    const commit=SaleCommand.create(SaleCommandKind.Commit,v3Identity,2,{}),commitHash=await SaleAdmissionFingerprint.hash(commit,7);
    const baseReceipt={...v3Identity,commandId:commit.commandId,kind:'Commit',actorId:7,cashSessionId:2,payloadHash:commitHash,intentId:3,outboxId:4,acceptedAt:'2026-10-08T00:00:00Z'};
    expect(PosWireMapper.saleAdmission(envelope({outcome:'Accepted',receipt:baseReceipt,failure:null},202),commit,7,commitHash).outcome).toBe(SaleAdmissionOutcome.Accepted);
    for(const fingerprintVersion of [SaleFingerprintVersion.AdmissionV2.wire,'future'])
      expect(PosWireMapper.saleAdmission(envelope({outcome:'Accepted',receipt:{...baseReceipt,fingerprintVersion},failure:null},202),commit,7,commitHash).outcome).toBe(SaleAdmissionOutcome.Unknown);
  });
  it('translates closed v3 line failures without echoing future codes',()=>{
    const command=SaleCommand.createMultilineReserve({...identity,saleId:'sale'},2,[{variantId:'a',quantity:1,expectedPriceVersion:'P1',sku:'SKU',productName:'Artículo',originalUnitPrice:'1',discountAmount:'0'}]);
    const rejected=PosWireMapper.saleAdmission(envelope({outcome:'Rejected',receipt:null,failure:'VALIDATION',lineFailures:[{variantId:'a',code:'VariantMissing'}]},400),command,7);
    expect(rejected.failure).toBe(SaleCommandFailure.Validation);expect(rejected.lineFailures?.[0].code).toBe(SaleLineFailureCode.VariantMissing);
    expect(PosWireMapper.saleAdmission(envelope({outcome:'Rejected',receipt:null,failure:'FORBIDDEN',lineFailures:[]},400),command,7).outcome).toBe(SaleAdmissionOutcome.Unknown);
    expect(PosWireMapper.saleAdmission(envelope({outcome:'Rejected',receipt:null,failure:'VALIDATION',lineFailures:[{variantId:'a',code:'future'}]},400),command,7).outcome).toBe(SaleAdmissionOutcome.Unknown);
  });
});
