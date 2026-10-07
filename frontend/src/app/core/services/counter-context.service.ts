import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { SessionStore } from './session.store';
import { StaffPermission } from '../domain/session-types';
import { API_BASE } from '../api';
import { PersistenceMode } from '../domain/pos-types';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { BaseResponse, isSuccessResponse } from '../models/base-response';
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
  private loadEpoch = 0;

  constructor() {
    this.identity.changed.subscribe(() => {
      this.invalidate();
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
    this.invalidate();
    if (!this.identity.can(StaffPermission.WorkspaceRead)) { this.loading.set(false); return; }
    const generation = this.identity.generation();
    const epoch = this.loadEpoch;
    const current = () => generation === this.identity.generation() && epoch === this.loadEpoch;
    this.loading.set(true);
    this.catalogError.set(null);
    let pending = 3;
    const finish = () => {
      if (!current()) return;
      pending -= 1;
      if (pending === 0) this.loading.set(false);
    };

    this.http.get<BaseResponse<WorkspaceWire>>(`${API_BASE}/workspace`).subscribe({
      next: (response) => {
        if (!current()) return;
        if (isSuccessResponse(response)) {
          const workspace = PosWireMapper.workspace(response.data);
          this.persistence.set(workspace.persistence);
        }
        finish();
      },
      error: () => finish(),
    });

    this.http.get<BaseResponse<CashSessionWire[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: (response) => {
        if (!current()) return;
        const sessions = isSuccessResponse(response) ? PosWireMapper.cashSessions(response.data) : [];
        this.openSession.set(sessions.find(item => item.status.isOpen && item.cashierId === this.cashierId())
          ?? (this.identity.staff()?.role.canAssignCashier ? sessions.find(item => item.status.isOpen) : null) ?? null);
        finish();
      },
      error: () => {
        if (!current()) return;
        this.openSession.set(null);
        finish();
      },
    });

    this.http.get<BaseResponse<CatalogSnapshot>>(`${API_BASE}/catalog`).subscribe({
      next: (response) => {
        if (!current()) return;
        this.catalog.set(isSuccessResponse(response) ? response.data : null);
        if (!this.catalog()) this.catalogError.set('Catálogo no disponible. No inicies una venta nueva.');
        finish();
      },
      error: () => {
        if (!current()) return;
        this.catalog.set(null);
        this.catalogError.set('Catálogo no disponible. No inicies una venta nueva.');
        finish();
      },
    });
  }

  invalidate(): void {
    this.loadEpoch++;
    this.openSession.set(null);
    this.persistence.set(PersistenceMode.Unknown);
    this.loading.set(true);
  }
}
