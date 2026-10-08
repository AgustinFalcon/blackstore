import { Injectable, computed, inject } from '@angular/core';
import { EMPTY, Observable, defer, filter, from, map, shareReplay, takeUntil } from 'rxjs';
import { SaleAdmission, SaleAdmissionOutcome, SaleCommand } from '../domain/sale-command';
import { CommandRuntimeStore } from './command-runtime.store';
import { SessionStore } from './session.store';
@Injectable({ providedIn: 'root' })
export class SaleCommandsStore {
  readonly runtime = inject(CommandRuntimeStore);
  private readonly session = inject(SessionStore);
  private deliveryEpoch=0;
  constructor(){this.session.changed.subscribe(()=>++this.deliveryEpoch);}
  readonly unresolved = computed(() => { const command = this.runtime.pending()?.command; return command instanceof SaleCommand ? command : null; });
  execute(command: SaleCommand): Observable<SaleAdmission> {
    const current=this.deliveryGuard();
    return defer(() => current()?from(this.runtime.execute(command)):EMPTY).pipe(map(result => 'receipt' in result ? result : { outcome: SaleAdmissionOutcome.Unknown, receipt: null }),
      takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }),filter(()=>current()));
  }
  consult(): Observable<SaleAdmission> {
    const current=this.deliveryGuard();
    return defer(() => current()?from(this.runtime.consult()):EMPTY).pipe(map(result => result && 'receipt' in result ? result : { outcome: SaleAdmissionOutcome.Unknown, receipt: null }),
      takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }),filter(()=>current()));
  }
  private deliveryGuard():()=>boolean {
    const epoch=this.deliveryEpoch,actor=this.session.staff()?.id,generation=this.session.generation();
    return ()=>epoch===this.deliveryEpoch && actor===this.session.staff()?.id && generation===this.session.generation();
  }
}
