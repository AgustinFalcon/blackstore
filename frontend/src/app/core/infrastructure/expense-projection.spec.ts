import { AccountingCommand, AccountingCommandKind, ExpenseOperation } from '../domain/accounting-command';
import { ExpenseProjectionPolicy, ExpenseProjectionState } from '../domain/expense-projection';
import { PaymentMethod } from '../domain/pos-types';
import { PosWireMapper } from './pos-wire-mapper';
import { commandEnvelope } from './accounting-command-test-helper';
import { expenseProjectionFixture } from './expense-projection-test-helper';
describe('expense projection boundary and frozen intention evidence',()=>{
  const command=(operation:ExpenseOperation)=>AccountingCommand.create(AccountingCommandKind.Expense,{cashSessionId:2,operation:operation.wire,amount:'10',category:'supplies',expenseId:5,paymentMethod:PaymentMethod.Cash.wire});
  for(const operation of [ExpenseOperation.Accrue,ExpenseOperation.AccrueAndSettle,ExpenseOperation.SettleExisting]){
    it('correlates '+operation.wire+' and rejects foreign actor/receipt/intent facts',()=>{
      const c=command(operation),raw=expenseProjectionFixture(c),p=PosWireMapper.expenseProjection(raw,200).projection!;
      const r=PosWireMapper.commandReceipt(commandEnvelope(c.commandId,{expenseId:5,settlementId:p.settlementId,ledgerEventIds:p.ledgerEventIds}),c.commandId);
      expect(ExpenseProjectionPolicy.accepts(c,r,p,7)).toBeTrue();
      for(const changed of [{commandId:crypto.randomUUID()},{cashSessionId:3},{expenseId:6},{settlementId:99},{ledgerEventIds:[99]},{committedAt:'2026-10-08T00:00:00Z'}])
        expect(ExpenseProjectionPolicy.accepts(c,{...r,...changed},p,7)).toBeFalse();
      expect(ExpenseProjectionPolicy.accepts(c,r,p,8)).toBeFalse();
      expect(ExpenseProjectionPolicy.accepts(c,r,{...p,postings:[...p.postings,p.postings[0]]},7)).toBeFalse();
      expect(ExpenseProjectionPolicy.accepts(c,r,{...p,postings:p.postings.map(v=>({...v,actorId:8}))},7)).toBeFalse();
      if(p.settlement)expect(ExpenseProjectionPolicy.accepts(c,r,{...p,settlement:{...p.settlement,paymentMethod:PaymentMethod.Card}},7)).toBeFalse();
    });
  }
  for(const [state,code] of [[ExpenseProjectionState.NotFound,404],[ExpenseProjectionState.Unavailable,503]] as const){
    it('decodes '+state.wire+' only with an empty matching envelope',()=>{
      expect(PosWireMapper.expenseProjection({code,traceId:'test',data:{state:state.wire,projection:null}},code).state).toBe(state);
      expect(PosWireMapper.expenseProjection({code:200,traceId:'test',data:{state:state.wire,projection:null}},code).state).toBe(ExpenseProjectionState.Unknown);
      expect(PosWireMapper.expenseProjection({code,traceId:'test',data:{state:state.wire,projection:{}}},code).state).toBe(ExpenseProjectionState.Unknown);
    });
  }
  it('neutralizes unknown/schema/incomplete/version/origin facts without displaying wire text',()=>{
    const c=command(ExpenseOperation.AccrueAndSettle);
    for(const edit of [(r:any)=>r.data.state='future',(r:any)=>delete r.data.projection.expense.paymentMethod,
      (r:any)=>r.data.projection.accountingEvidence.completeness='future',(r:any)=>r.data.projection.accountingEvidence.accountingVersion=1,
      (r:any)=>r.data.projection.accountingEvidence.postings[0].origin.id='99',(r:any)=>r.data.projection.accountingEvidence.postings[0].component='future',
      (r:any)=>r.data.projection.accountingEvidence.snapshot.cutoff='bad',(r:any)=>r.data.projection.operation='future']){
      const raw=expenseProjectionFixture(c);edit(raw);expect(PosWireMapper.expenseProjection(raw,200).state).toBe(ExpenseProjectionState.Unknown);
    }
  });
  it('accepts exact HTTP200 Found only without error evidence',()=>{
    const raw=expenseProjectionFixture(command(ExpenseOperation.AccrueAndSettle));
    expect(PosWireMapper.expenseProjection(raw,200).state).toBe(ExpenseProjectionState.Found);
    expect(PosWireMapper.expenseProjection(raw,503).state).toBe(ExpenseProjectionState.Unknown);
    expect(PosWireMapper.expenseProjection({...raw,errorCode:'UNAVAILABLE'},200).state).toBe(ExpenseProjectionState.Unknown);
    expect(PosWireMapper.expenseProjection({...raw,error:{message:'unavailable'}},200).state).toBe(ExpenseProjectionState.Unknown);
    expect(PosWireMapper.expenseProjection({...raw,errorCode:undefined},200).state).toBe(ExpenseProjectionState.Unknown);
  });
});
