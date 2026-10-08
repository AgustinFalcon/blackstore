import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, concatMap, finalize, map, of, shareReplay, takeUntil, tap } from 'rxjs';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingCommand, CommandFailure, CommandOutcome, CommandReceipt } from '../domain/accounting-command';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { SessionStore } from './session.store';

/** One POST per human intent. Recovery only reads the same durable command identity. */
@Injectable({ providedIn: 'root' })
export class AccountingCommandsStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  readonly unresolved = signal<AccountingCommand | null>(null);
  readonly blocked = signal(CommandFailure.None);
  readonly receipt = signal<CommandReceipt | null>(null);
  private pendingQuery: { commandId: string; response: Observable<CommandReceipt> } | null = null;
  constructor() {
    this.session.changed.subscribe(() => { this.unresolved.set(null); this.receipt.set(null); this.blocked.set(CommandFailure.None); });
  }
  execute(command: AccountingCommand): Observable<CommandReceipt> {
    if (this.unresolved() || this.blocked().blocksWrites || !this.session.can(command.kind.permission)) {
      return of(PosWireMapper.commandReceipt(null, command.commandId));
    }
    this.unresolved.set(command);
    return this.http.post<unknown>(`${ACCOUNTING_API_BASE}${PosWireMapper.commandPath(command)}`, command.body).pipe(
      catchError(error => of(error instanceof HttpErrorResponse ? error.error : null)),
      map(response => this.translate(response, command)),
      concatMap(receipt => receipt.outcome !== CommandOutcome.Committed &&
        (receipt.failure === CommandFailure.None || receipt.failure === CommandFailure.Unknown || receipt.failure === CommandFailure.Unavailable)
        ? this.query(command) : of(receipt)),
      tap(receipt => this.record(command, receipt)), takeUntil(this.session.changed),
    );
  }
  consult(): Observable<CommandReceipt> {
    const command = this.unresolved();
    return command ? this.query(command).pipe(tap(receipt => this.record(command, receipt)), takeUntil(this.session.changed))
      : of(PosWireMapper.commandReceipt(null, ''));
  }
  private query(command: AccountingCommand): Observable<CommandReceipt> {
    if (this.pendingQuery?.commandId === command.commandId) return this.pendingQuery.response;
    const pending = { commandId: command.commandId, response: this.http.get<unknown>(`${ACCOUNTING_API_BASE}/accounting/commands/${command.commandId}`).pipe(
      catchError(error => of(error instanceof HttpErrorResponse ? error.error : null)),
      map(response => this.translate(response, command)),
      takeUntil(this.session.changed),
      finalize(() => { if (this.pendingQuery === pending) this.pendingQuery = null; }),
      shareReplay({ bufferSize: 1, refCount: true }),
    ) };
    this.pendingQuery = pending;
    return pending.response;
  }
  private translate(response: unknown, command: AccountingCommand): CommandReceipt {
    const receipt = PosWireMapper.commandReceipt(response, command.commandId);
    return command.accepts(receipt) ? receipt : PosWireMapper.commandReceipt(null, command.commandId);
  }
  private record(command: AccountingCommand, receipt: CommandReceipt): void {
    // A subscriber may begin the next command while the previous response is still being delivered.
    if (this.unresolved()?.commandId !== command.commandId) return;
    this.receipt.set(receipt);
    if (receipt.failure.blocksWrites) this.blocked.set(receipt.failure);
    if (receipt.outcome === CommandOutcome.Committed ||
      (receipt.failure !== CommandFailure.None && receipt.failure !== CommandFailure.Unknown && receipt.failure !== CommandFailure.Unavailable)) {
      this.unresolved.set(null);
    }
  }
}
