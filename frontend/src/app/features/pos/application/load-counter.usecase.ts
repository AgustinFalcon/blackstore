import { Injectable, inject } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ApiFailure, copyForErrorCode } from '../../../core/http/api-error';
import { PosStore } from '../application/pos.store';
import { BlackstoreApiService } from '../infrastructure/blackstore-api.service';

@Injectable({ providedIn: 'root' })
export class LoadCounterUseCase {
  private readonly api = inject(BlackstoreApiService);
  private readonly store = inject(PosStore);

  refresh(): void {
    this.store.counterLoading.set(true);
    this.store.catalogError.set(null);
    this.store.sessionsError.set(null);
    forkJoin({
      workspace: this.api.workspace(),
      sessions: this.api.cashSessions(),
      catalog: this.api.catalog(),
    }).subscribe((result) => {
      if (result.workspace.ok) this.store.workspace.set(result.workspace.data);
      if (result.sessions.ok) this.store.sessions.set(result.sessions.data);
      else this.store.sessionsError.set(screenCopy(result.sessions.failure, 'No se pudo leer la caja'));
      if (result.catalog.ok) {
        this.store.catalog.set(result.catalog.data);
        this.store.catalogUnavailable.set(false);
      } else {
        this.store.catalog.set(null);
        this.store.catalogUnavailable.set(true);
        this.store.catalogError.set(screenCopy(result.catalog.failure, 'Catálogo no disponible'));
        this.noteFailure(result.catalog.failure);
      }
      this.store.counterLoading.set(false);
    });
  }

  private noteFailure(failure: ApiFailure): void {
    if (failure.errorCode === 'CAPABILITY_DISABLED') this.store.blockWrites();
    if (failure.errorCode === 'CATALOG_VERSION_STALE') this.store.markCatalogStale();
  }
}

export function screenCopy(failure: ApiFailure, fallback: string): string {
  if (failure.errorCode === 'UNKNOWN') return fallback;
  return copyForErrorCode(failure.errorCode);
}
