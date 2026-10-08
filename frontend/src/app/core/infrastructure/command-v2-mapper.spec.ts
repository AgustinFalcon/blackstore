import { PosWireMapper } from './pos-wire-mapper';
import { PosContextState } from '../domain/pos-execution-context';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { SaleCommand, SaleCommandKind, SaleAdmissionOutcome } from '../domain/sale-command';
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
    it('correlates accepted '+kind.wire+' without commercial terminality',()=>{
      const command=SaleCommand.create(kind,identity,2,{reason:'  motivo  '});
      const receipt={...identity,commandId:command.commandId,kind:kind.wire,actorId:7,cashSessionId:2,payloadHash:'a'.repeat(64),intentId:1,outboxId:2,acceptedAt:'2026-10-08T00:00:00Z'};
      const response=envelope({outcome:SaleAdmissionOutcome.Accepted.wire,receipt,failure:null},202);
      expect(PosWireMapper.saleAdmission(response,command,7).outcome).toBe(SaleAdmissionOutcome.Accepted);
      expect(command.body['reason']).toBe('motivo');expect(command.body['feeAmount']).toBeUndefined();
      for(const change of [{actorId:8},{cashSessionId:3},{commandId:crypto.randomUUID()},{operationId:crypto.randomUUID()},{kind:'future'},{payloadHash:'bad'},{acceptedAt:'bad'},{intentId:0},{outboxId:0}])
        expect(PosWireMapper.saleAdmission(envelope({outcome:SaleAdmissionOutcome.Accepted.wire,receipt:{...receipt,...change},failure:null},202),command,7).outcome).toBe(SaleAdmissionOutcome.Unknown);
    });
  }
  it('does not infer acceptance from status 202 or an absent receipt',()=>{
    const command=SaleCommand.create(SaleCommandKind.Reserve,identity,2,{});
    expect(PosWireMapper.saleAdmission(envelope({outcome:SaleAdmissionOutcome.Accepted.wire,receipt:null,failure:null},202),command,7).outcome).toBe(SaleAdmissionOutcome.Unknown);
  });
});
