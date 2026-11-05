import { Injectable, computed, inject, signal } from '@angular/core';
import { EMPTY, Observable, defer, filter, from, map, shareReplay, takeUntil, tap } from 'rxjs';
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
  private deliveryEpoch=0;
  constructor() { this.session.changed.subscribe(() => {++this.deliveryEpoch;this.receipt.set(null);}); }
  execute(command: AccountingCommand): Observable<CommandReceipt> {
    const current=this.deliveryGuard();
    return defer(() => current()?from(this.runtime.execute(command)):EMPTY).pipe(map(result => 'failure' in result ? result : PosWireMapper.commandReceipt(null,command.commandId)),
      tap(receipt => {if(current())this.receipt.set(receipt);}), takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }),filter(()=>current()));
  }
  consult(): Observable<CommandReceipt> {
    const current=this.deliveryGuard();
    return defer(() => current()?from(this.runtime.consult()):EMPTY).pipe(map(result => result && 'failure' in result ? result : PosWireMapper.commandReceipt(null,'')),
      tap(receipt => {if(current())this.receipt.set(receipt);}), takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }),filter(()=>current()));
  }
  private deliveryGuard():()=>boolean {
    const epoch=this.deliveryEpoch,actor=this.session.staff()?.id,generation=this.session.generation();
    return ()=>epoch===this.deliveryEpoch && actor===this.session.staff()?.id && generation===this.session.generation();
  }
}
