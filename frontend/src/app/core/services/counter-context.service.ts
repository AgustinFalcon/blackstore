import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { SessionStore } from './session.store';
import { StaffPermission } from '../domain/session-types';
import { API_BASE } from '../api';
import { PersistenceMode } from '../domain/pos-types';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { BaseResponse } from '../models/base-response';
import { CashSessionData, CashSessionWire, WorkspaceWire } from '../models/pos-models';

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

@Injectable({ providedIn: 'root' })
export class CounterContextService {
  private readonly http = inject(HttpClient);
  private readonly identity = inject(SessionStore);

  constructor() {
    this.identity.changed.subscribe(() => {
      this.catalog.set(null); this.openSession.set(null); this.persistence.set(PersistenceMode.Unknown);
      this.catalogError.set(null); this.loading.set(false);
    });
  }

  readonly loading = signal(true);
  readonly catalog = signal<CatalogSnapshot | null>(null);
  readonly catalogError = signal<string | null>(null);
  readonly openSession = signal<CashSessionData | null>(null);
  readonly cashierId = computed(() => this.identity.staff()?.id ?? null);
  readonly persistence = signal(PersistenceMode.Unknown);

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
    if (!this.identity.can(StaffPermission.WorkspaceRead)) { this.loading.set(false); return; }
    this.loading.set(true);
    this.catalogError.set(null);
    let pending = 3;
    const finish = () => {
      pending -= 1;
      if (pending === 0) this.loading.set(false);
    };

    this.http.get<BaseResponse<WorkspaceWire>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (response.data) {
          const workspace = PosWireMapper.workspace(response.data);
          this.persistence.set(workspace.persistence);
        }
        finish();
      },
      error: () => finish(),
    });

    this.http.get<BaseResponse<CashSessionWire[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: (response) => {
        const sessions = PosWireMapper.cashSessions(response.data);
        this.openSession.set(sessions.find(item => item.status.isOpen && item.cashierId === this.cashierId())
          ?? (this.identity.staff()?.role.canAssignCashier ? sessions.find(item => item.status.isOpen) : null) ?? null);
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
