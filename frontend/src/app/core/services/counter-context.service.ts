import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { API_BASE } from '../api';
import { BaseResponse } from '../models/base-response';

export interface CatalogItem {
  sku: string;
  name: string;
  variantId: string;
  priceVersion?: string | null;
  unitPrice?: number | null;
}

export interface CatalogSnapshot {
  version: string | null;
  stale: boolean;
  importedAt: string | null;
  validUntil: string | null;
  items: CatalogItem[];
}

export interface OpenCashSession {
  id: number;
  terminalId: number;
  cashierId: number;
  status: string;
  openingCash: number;
}

@Injectable({ providedIn: 'root' })
export class CounterContextService {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly catalog = signal<CatalogSnapshot | null>(null);
  readonly catalogError = signal<string | null>(null);
  readonly openSession = signal<OpenCashSession | null>(null);
  readonly cashierId = signal(1);
  readonly persistence = signal('memory');

  readonly blockReason = computed(() => {
    if (this.loading()) return 'Comprobando caja y catálogo…';
    if (this.catalogError()) return this.catalogError();
    const snapshot = this.catalog();
    if (!snapshot || snapshot.stale || snapshot.items.length === 0) {
      return 'Catálogo vencido o vacío. No inicies una venta nueva.';
    }
    if (!this.openSession()) return 'No hay sesión de caja abierta.';
    return null;
  });

  load(): void {
    this.loading.set(true);
    this.catalogError.set(null);
    let pending = 3;
    const finish = () => {
      pending -= 1;
      if (pending === 0) this.loading.set(false);
    };

    this.http.get<BaseResponse<{ terminalId: number; cashierId: number; persistence: string }>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (response.data) {
          this.cashierId.set(response.data.cashierId);
          this.persistence.set(response.data.persistence);
        }
        finish();
      },
      error: () => finish(),
    });

    this.http.get<BaseResponse<OpenCashSession[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: (response) => {
        this.openSession.set(response.data?.find((item) => item.status === 'OPEN') ?? null);
        finish();
      },
      error: () => {
        this.openSession.set(null);
        finish();
      },
    });

    this.http.get<BaseResponse<CatalogSnapshot>>(`${API_BASE}/catalog`).subscribe({
      next: (response) => {
        this.catalog.set(response.data);
        if (!response.data) this.catalogError.set('Catálogo no disponible. No inicies una venta nueva.');
        finish();
      },
      error: () => {
        this.catalog.set(null);
        this.catalogError.set('Catálogo no disponible. No inicies una venta nueva.');
        finish();
      },
    });
  }
}
