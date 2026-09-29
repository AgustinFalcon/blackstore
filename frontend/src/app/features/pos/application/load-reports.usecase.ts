import { Injectable, inject } from '@angular/core';
import { PosStore } from '../application/pos.store';
import { BlackstoreApiService } from '../infrastructure/blackstore-api.service';
import { screenCopy } from './load-counter.usecase';

@Injectable({ providedIn: 'root' })
export class LoadReportsUseCase {
  private readonly api = inject(BlackstoreApiService);
  private readonly store = inject(PosStore);

  refresh(): void {
    this.store.reportsLoading.set(true);
    this.store.reportsError.set(null);
    this.store.dailyError.set(null);
    this.api.shiftReport().subscribe((result) => {
      this.store.reportsLoading.set(false);
      if (!result.ok) {
        this.store.shiftReport.set(null);
        this.store.reportsError.set(screenCopy(result.failure, 'Reporte no disponible'));
        return;
      }
      this.store.shiftReport.set(result.data);
    });
    this.api.dailyReport().subscribe((result) => {
      if (!result.ok) {
        this.store.dailyReport.set(null);
        this.store.dailyError.set(screenCopy(result.failure, 'Reporte no disponible'));
        return;
      }
      this.store.dailyReport.set(result.data);
    });
  }
}
