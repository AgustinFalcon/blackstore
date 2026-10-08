import { Injectable, computed, inject } from '@angular/core';
import { Observable, defer, from, map, shareReplay, takeUntil } from 'rxjs';
import { SaleAdmission, SaleAdmissionOutcome, SaleCommand } from '../domain/sale-command';
import { CommandRuntimeStore } from './command-runtime.store';
import { SessionStore } from './session.store';
@Injectable({ providedIn: 'root' })
export class SaleCommandsStore {
  readonly runtime = inject(CommandRuntimeStore);
  private readonly session = inject(SessionStore);
  readonly unresolved = computed(() => { const command = this.runtime.pending()?.command; return command instanceof SaleCommand ? command : null; });
  execute(command: SaleCommand): Observable<SaleAdmission> {
    return defer(() => from(this.runtime.execute(command))).pipe(map(result => 'receipt' in result ? result : { outcome: SaleAdmissionOutcome.Unknown, receipt: null }),
      takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }));
  }
  consult(): Observable<SaleAdmission> {
    return defer(() => from(this.runtime.consult())).pipe(map(result => result && 'receipt' in result ? result : { outcome: SaleAdmissionOutcome.Unknown, receipt: null }),
      takeUntil(this.session.changed), shareReplay({ bufferSize:1, refCount:false }));
  }
}
