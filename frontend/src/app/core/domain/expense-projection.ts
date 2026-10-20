import { AccountingCommand, AccountingCommandKind, CommandOutcome, CommandReceipt, ExpenseOperation } from './accounting-command';
import { PaymentMethod } from './pos-types';
import { TicketMoney } from './ticket-transition';

export class ExpenseProjectionState {
  static readonly Found=new ExpenseProjectionState('FOUND','Gasto comprobado');
  static readonly NotFound=new ExpenseProjectionState('NOT_FOUND','Proyección no visible');
  static readonly Unavailable=new ExpenseProjectionState('UNAVAILABLE','Proyección no disponible');
  static readonly Unknown=new ExpenseProjectionState('UNKNOWN','Proyección desconocida');
  private constructor(readonly wire:string,readonly label:string){}
  static fromWire(raw:unknown):ExpenseProjectionState{return [this.Found,this.NotFound,this.Unavailable].find(v=>v.wire===raw)??this.Unknown;}
}
export class ExpensePostingKind {
  static readonly Accrual=new ExpensePostingKind('EXPENSE_ACCRUAL','EXPENSE_ACCRUAL');
  static readonly Paid=new ExpensePostingKind('EXPENSE_PAID','EXPENSE_SETTLEMENT');
  static readonly Unknown=new ExpensePostingKind('UNKNOWN','UNKNOWN');
  private constructor(readonly wire:string,readonly component:string){}
  static fromWire(kind:unknown,component:unknown):ExpensePostingKind{return [this.Accrual,this.Paid].find(v=>v.wire===kind && v.component===component)??this.Unknown;}
}
export interface ExpenseFact {
  readonly id:number;readonly cashSessionId:number;readonly actorId:number;readonly category:string;
  readonly amount:TicketMoney;readonly createdAt:string;readonly paymentMethod:PaymentMethod|null;
}
export interface ExpenseSettlement {
  readonly id:number;readonly expenseId:number;readonly cashSessionId:number;readonly actorId:number;
  readonly commandId:string;readonly amount:TicketMoney;readonly paymentMethod:PaymentMethod;readonly paidAt:string;
}
export interface ExpensePosting {
  readonly id:number;readonly kind:ExpensePostingKind;readonly commandId:string;readonly cashSessionId:number;
  readonly expenseId:number;readonly actorId:number;readonly amount:TicketMoney;readonly paymentMethod:PaymentMethod;readonly occurredAt:string;
}
export interface ExpenseProjection {
  readonly commandId:string;readonly operation:ExpenseOperation;readonly cashSessionId:number;readonly expenseId:number;
  readonly settlementId:number|null;readonly ledgerEventIds:readonly number[];readonly expense:ExpenseFact;
  readonly settlement:ExpenseSettlement|null;readonly committedAt:string;readonly postings:readonly ExpensePosting[];
  readonly snapshot:Readonly<{id:string;cutoff:string;asOf:string}>;
}
export interface ExpenseProjectionObservation {readonly state:ExpenseProjectionState;readonly projection:ExpenseProjection|null;}

/** Correlates frozen intent, receipt and the command-scoped consistent read. No balance inference. */
export class ExpenseProjectionPolicy {
  static accepts(command:AccountingCommand,receipt:CommandReceipt,projection:ExpenseProjection,actor:number):boolean {
    const p=projection,e=p.expense,s=p.settlement,operation=ExpenseOperation.fromWire(command.body['operation']);
    if(command.kind!==AccountingCommandKind.Expense || receipt.outcome!==CommandOutcome.Committed || receipt.paymentId!==null || receipt.closeSnapshot!==null || !command.accepts(receipt) ||
      p.commandId!==command.commandId || p.commandId!==receipt.commandId || p.cashSessionId!==command.body['cashSessionId'] ||
      p.cashSessionId!==receipt.cashSessionId || p.expenseId!==receipt.expenseId || p.settlementId!==receipt.settlementId ||
      p.operation!==operation || operation===ExpenseOperation.Unknown || p.committedAt!==receipt.committedAt ||
      !receipt.ledgerEventIds || p.ledgerEventIds.length!==receipt.ledgerEventIds.length ||
      !p.ledgerEventIds.every(id=>receipt.ledgerEventIds!.includes(id)) || e.id!==p.expenseId || e.cashSessionId!==p.cashSessionId ||
      Date.parse(e.createdAt)>Date.parse(p.committedAt))return false;
    if(operation===ExpenseOperation.SettleExisting){if(e.id!==command.body['expenseId'])return false;}
    else if(e.actorId!==actor || e.category!==command.body['category'] || e.amount.cents!==TicketMoney.fromDecimal(command.body['amount'])?.cents || e.createdAt!==p.committedAt)return false;
    if(p.postings.length!==p.ledgerEventIds.length || new Set(p.postings.map(v=>v.id)).size!==p.postings.length ||
      !p.postings.every(v=>p.ledgerEventIds.includes(v.id) && v.actorId===actor && v.commandId===p.commandId &&
        v.cashSessionId===p.cashSessionId && v.expenseId===e.id && v.occurredAt===p.committedAt))return false;
    const accrual=p.postings.filter(v=>v.kind===ExpensePostingKind.Accrual),paid=p.postings.filter(v=>v.kind===ExpensePostingKind.Paid);
    if(accrual.length!==(operation===ExpenseOperation.SettleExisting?0:1) || paid.length!==(operation===ExpenseOperation.Accrue?0:1) || accrual.length+paid.length!==p.postings.length)return false;
    if(operation===ExpenseOperation.Accrue){if(s!==null || p.settlementId!==null || e.paymentMethod!==null)return false;}
    else if(!s || s.id!==p.settlementId || s.expenseId!==e.id || s.cashSessionId!==p.cashSessionId || s.actorId!==actor ||
      s.commandId!==p.commandId || s.paidAt!==p.committedAt || s.amount.cents!==e.amount.cents ||
      s.paymentMethod!==PaymentMethod.fromWire(command.body['paymentMethod']) || s.paymentMethod===PaymentMethod.Unknown ||
      e.paymentMethod!==s.paymentMethod || paid[0].paymentMethod!==s.paymentMethod || paid[0].amount.cents!==-e.amount.cents)return false;
    return accrual.every(v=>v.amount.cents===e.amount.cents && v.paymentMethod===(s?.paymentMethod??PaymentMethod.Other));
  }
}
