import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, defer, from, map, shareReplay, takeUntil, tap } from 'rxjs';
import { AccountingCommand, CommandFailure, CommandReceipt } from '../domain/accounting-command';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { SessionStore } from './session.store';
import { CommandRuntimeStore } from './command-runtime.store';
@Injectable({ providedIn: 'root' })
export class AccountingCommandsStore {
  readonly runtime = inject(CommandRuntimeStore);
  private readonly session = inject(SessionStore);
  readonly unresolved = computed(() => { const command = this.runtime.pending()?.command; return command instanceof AccountingCommand ? command : null; });
  readonly blocked = computed(() => this.runtime.canWrite() ? CommandFailure.None : CommandFailure.Paused);
  readonly receipt = signal<CommandReceipt | null>(null);
  constructor() { this.session.changed.subscribe(() => this.receipt.set(null)); }
  execute(command: AccountingCommand): Observable<CommandReceipt> {
    return defer(() => from(this.runtime.execute(command))).pipe(map(result => 'failure' in result ? result : PosWireMapper.commandReceipt(null,command.commandId)),
      tap(receipt => this.receipt.set(receipt)), takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }));
  }
  consult(): Observable<CommandReceipt> {
    return defer(() => from(this.runtime.consult())).pipe(map(result => result && 'failure' in result ? result : PosWireMapper.commandReceipt(null,'')),
      tap(receipt => this.receipt.set(receipt)), takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }));
  }
}
