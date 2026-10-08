import { AccountingCommand, ExpenseOperation } from '../domain/accounting-command';
import { ExpensePostingKind, ExpenseProjectionState } from '../domain/expense-projection';
import { DataCompleteness } from '../domain/accounting-report';
import { PaymentMethod } from '../domain/pos-types';
export function expenseProjectionFixture(command:AccountingCommand,actor=7){
  const operation=ExpenseOperation.fromWire(command.body['operation']),paid=operation!==ExpenseOperation.Accrue;
  const committedAt='2026-10-07T12:00:00Z',method=paid?PaymentMethod.Cash:PaymentMethod.Other;
  const posting=(id:number,kind:ExpensePostingKind)=>({id,kind:kind.wire,component:kind.component,paymentMethod:method.wire,
    amount:kind===ExpensePostingKind.Accrual?'10':'-10',origin:{kind:'EXPENSE',id:'5'},occurredAt:committedAt,actorId:actor,
    commandId:command.commandId,cashSessionId:2,expenseId:5,accountingVersion:2});
  const postings=operation===ExpenseOperation.Accrue?[posting(1,ExpensePostingKind.Accrual)]:operation===ExpenseOperation.SettleExisting?[posting(2,ExpensePostingKind.Paid)]:[posting(1,ExpensePostingKind.Accrual),posting(2,ExpensePostingKind.Paid)];
  return {code:200,traceId:'test',errorCode:null,message:null,retryable:null,data:{state:ExpenseProjectionState.Found.wire,projection:{commandId:command.commandId,kind:'EXPENSE_RECORD',operation:operation.wire,
    cashSessionId:2,expenseId:5,settlementId:paid?6:null,ledgerEventIds:postings.map(p=>p.id),
    expense:{id:5,cashSessionId:2,category:'supplies',amount:'10',actorId:operation===ExpenseOperation.SettleExisting?9:actor,
      createdAt:operation===ExpenseOperation.SettleExisting?'2026-10-06T12:00:00Z':committedAt,paymentMethod:paid?method.wire:null},
    settlement:paid?{id:6,expenseId:5,cashSessionId:2,amount:'10',paymentMethod:method.wire,actorId:actor,commandId:command.commandId,paidAt:committedAt}:null,
    accountingEvidence:{committedAt,postings,accountingVersion:2,completeness:DataCompleteness.Complete.wire,causes:[],
      snapshot:{id:'snapshot',cutoff:committedAt,asOf:'2026-10-08T12:00:00Z'}}}}};
}
