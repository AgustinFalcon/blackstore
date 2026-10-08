import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { API_BASE, ACCOUNTING_API_BASE } from '../api';
import { AccountingCommand, AccountingCommandKind, CommandReceipt } from '../domain/accounting-command';
import { SaleCommand, SaleCommandKind } from '../domain/sale-command';
import { PosWireMapper } from './pos-wire-mapper';
import { PaymentStatus } from '../domain/pos-types';
import { DurableSaleState } from '../domain/durable-sale';
import { TicketMoney } from '../domain/ticket-transition';
import { PaymentMethod } from '../domain/pos-types';
import {AwaitReservationReadStep,ReservationReadState} from './await-reservation-read-step';
@Injectable({ providedIn: 'root' })
export class CommandHttp {
  private readonly http = inject(HttpClient);
  context(): Observable<unknown> { return this.safe(this.http.get(`${ACCOUNTING_API_BASE}/pos/context`)); }
  lifecycle(): Observable<unknown> { return this.safe(this.http.get(`${ACCOUNTING_API_BASE}/accounting/runtime`)); }
  post(command: AccountingCommand | SaleCommand): Observable<unknown> {
    const path = command instanceof AccountingCommand ? PosWireMapper.commandPath(command) : command.kind === SaleCommandKind.Reserve ? '/sales/reservations' :
      `/sales/${encodeURIComponent(command.identity.operationId)}/${command.kind === SaleCommandKind.Commit ? 'commit' : 'release'}`;
    return this.safe(this.http.post(`${ACCOUNTING_API_BASE}${path}`, command.body));
  }
  receipt(command: AccountingCommand | SaleCommand): Observable<unknown> {
    return this.safe(this.http.get(`${ACCOUNTING_API_BASE}/${command instanceof SaleCommand ? 'sales' : 'accounting'}/commands/${command.commandId}`));
  }
  ticket(identity: import('../domain/ticket-transition').TicketIdentity): Observable<unknown> { return this.http.get(`${API_BASE}/sales/${encodeURIComponent(identity.operationId)}`); }
  references(command: AccountingCommand | SaleCommand, terminalId: number): Observable<number | null | false> {
    if (command instanceof AccountingCommand && command.kind === AccountingCommandKind.Open) return of(command.body['terminalId'] === terminalId ? null : false);
    const body = command.body;
    const identity = command instanceof SaleCommand ? command.identity : body as unknown as import('../domain/ticket-transition').TicketIdentity;
    const sale = command instanceof SaleCommand && command.kind === SaleCommandKind.Reserve || !identity.operationId
      ? of(command instanceof SaleCommand ? command.cashSessionId : body['cashSessionId'] as number ?? command.aggregateId)
      : this.http.get(`${API_BASE}/sales/operations/${encodeURIComponent(identity.operationId)}`).pipe(map(raw => {
        const detail = PosWireMapper.durableDetail(raw,identity.operationId);
        if (!detail?.valid || !importIdentity(detail.identity,identity)) throw new Error('Venta no comprobada');
        if(command instanceof AccountingCommand && command.kind===AccountingCommandKind.Reverse &&
          !detail.payments.some(p=>p.paymentId===command.body['originalPaymentId'] && p.reversibility.permitsReverse)) throw new Error('Pago no reversible');
        return detail.cashSessionId;
      }));
    return sale.pipe(importConcatMap(cashSessionId => this.http.get<import('../models/base-response').BaseResponse<import('../models/pos-models').CashSessionWire[]>>(`${API_BASE}/cash-sessions`).pipe(map(raw => {
      const session = raw.code === 200 && Array.isArray(raw.data) ? PosWireMapper.cashSessions(raw.data).find(s => s.id === cashSessionId) : null;
      return session && session.terminalId === terminalId && session.status.isOpen ? session.id : false as const;
    }))),catchError(() => of(false as const)));
  }
  refresh(command: AccountingCommand | SaleCommand, receipt?: CommandReceipt,current:()=>boolean=()=>true): Observable<boolean> {
    // Current API has no authoritative expense/settlement read. Keep the journal unresolved.
    if(command instanceof AccountingCommand && command.kind===AccountingCommandKind.Expense) return of(false);
    if(command instanceof SaleCommand && command.kind===SaleCommandKind.Reserve){
      const read=()=>this.http.get(`${API_BASE}/sales/operations/${encodeURIComponent(command.identity.operationId)}`).pipe(map(raw=>PosWireMapper.durableDetail(raw,command.identity.operationId)));
      return new AwaitReservationReadStep(read,detail=>{
        if(!detail || !importIdentity(detail.identity,command.identity) || detail.cashSessionId!==command.cashSessionId)return ReservationReadState.Unknown;
        if(detail.status===DurableSaleState.PendingReservation)return ReservationReadState.Pending;
        return detail.status===DurableSaleState.Reserved && detail.valid ? ReservationReadState.Reserved : ReservationReadState.Unknown;
      },current).execute().pipe(map(()=>true),catchError(()=>of(false)));
    }
    if (command instanceof SaleCommand || command.kind === AccountingCommandKind.Capture || command.kind === AccountingCommandKind.Reverse) {
      const identity = command instanceof SaleCommand ? command.identity : command.body as unknown as import('../domain/ticket-transition').TicketIdentity;
      return this.http.get(`${API_BASE}/sales/operations/${encodeURIComponent(identity.operationId)}`).pipe(map(raw => {
        const detail = PosWireMapper.durableDetail(raw, identity.operationId);
        if (!detail || !detail.valid || !importIdentity(detail.identity, identity)) return false;
        if (command instanceof SaleCommand) {
          if (detail.cashSessionId !== command.cashSessionId) return false;
          const state = detail.status;
          return command.kind === SaleCommandKind.Reserve ? state === DurableSaleState.Reserved : command.kind === SaleCommandKind.Commit ? state === DurableSaleState.Committed : state === DurableSaleState.Released;
        }
        return detail.cashSessionId === receipt?.cashSessionId && !!receipt?.paymentId && detail.payments.some(p => p.paymentId === receipt.paymentId &&
          (command.kind === AccountingCommandKind.Capture ? p.status === PaymentStatus.Captured && p.amount?.cents === TicketMoney.fromDecimal(command.body['amount'])?.cents &&
            p.method === PaymentMethod.fromWire(command.body['paymentMethod']) : p.status === PaymentStatus.Refunded || p.status === PaymentStatus.Voided));
      }), catchError(() => of(false)));
    }
    return this.http.get<import('../models/base-response').BaseResponse<import('../models/pos-models').CashSessionWire[]>>(`${API_BASE}/cash-sessions`).pipe(map(response => {
      const sessions = response.code === 200 && Array.isArray(response.data) ? PosWireMapper.cashSessions(response.data) : [];
      const session = sessions.find(s => s.id === receipt?.cashSessionId);
      if (!session || session.terminalId < 1 || session.cashierId < 1) return false;
      if (command.kind === AccountingCommandKind.Open) return session.status.isOpen && session.terminalId === command.body['terminalId'] && session.cashierId === command.body['cashierId'];
      if (command.kind === AccountingCommandKind.Close) return session.status.isClosed;
      return false;
    }), catchError(() => of(false)));
  }
  private safe(request: Observable<unknown>): Observable<unknown> { return request.pipe(catchError(error => of(error instanceof HttpErrorResponse ? error.error : null))); }
}
import { sameTicketIdentity as importIdentity } from '../domain/ticket-transition';
import { concatMap as importConcatMap } from 'rxjs';
