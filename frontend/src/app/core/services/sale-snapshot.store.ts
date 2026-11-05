import { HttpClient, HttpParams } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, map, of, switchMap, takeUntil, throwError } from 'rxjs';
import { API_BASE } from '../api';
import { DurableSaleDetail, DurableSaleSummary } from '../domain/durable-sale';
import { SaleRouteId, SaleSnapshotState } from '../domain/sale-snapshot-state';
import { StaffPermission } from '../domain/session-types';
import { TicketIdentity, sameTicketIdentity } from '../domain/ticket-transition';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { SessionStore } from './session.store';

interface SaleRouteResolution {
  readonly state: SaleSnapshotState;
  readonly identity: TicketIdentity | null;
}

/** Read-only route resolver. A sale id never becomes write authority. */
@Injectable()
export class SaleSnapshotStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cancelLookup = new Subject<void>();
  private generation = 0;
  private requested: SaleRouteId | null = null;
  readonly state = signal(SaleSnapshotState.Idle);
  readonly detail = signal<DurableSaleDetail | null>(null);

  constructor() {
    this.destroyRef.onDestroy(() => { this.cancelLookup.next(); this.cancelLookup.complete(); });
    this.session.changed.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.cancelLookup.next();
      ++this.generation;
      this.requested = null;
      this.detail.set(null);
      this.state.set(SaleSnapshotState.Idle);
    });
  }

  load(rawSaleId: unknown): void {
    this.cancelLookup.next();
    const saleId = SaleRouteId.fromWire(rawSaleId);
    const request = ++this.generation;
    this.requested = saleId;
    this.detail.set(null);
    if (!saleId) { this.state.set(SaleSnapshotState.Invalid); return; }
    if (!this.session.can(StaffPermission.SaleRead)) { this.state.set(SaleSnapshotState.Unavailable); return; }
    this.state.set(SaleSnapshotState.Loading);
    this.resolve(saleId, null, new Set<string>(), null).pipe(
      switchMap(resolution => {
        if (resolution.state !== SaleSnapshotState.Found || !resolution.identity) return of({ resolution, detail: null });
        return this.http.get<unknown>(`${API_BASE}/sales/operations/${encodeURIComponent(resolution.identity.operationId)}`).pipe(
          map(response => {
            const detail = PosWireMapper.durableDetail(response, resolution.identity!.operationId);
            if (!detail || !saleId.matches(detail.identity.saleId) || !sameTicketIdentity(detail.identity, resolution.identity!)) {
              throw new Error('incompatible sale snapshot');
            }
            return { resolution, detail };
          }),
        );
      }),
      takeUntil(this.cancelLookup),
      takeUntilDestroyed(this.destroyRef),
      catchError(() => of({ resolution: this.resolution(SaleSnapshotState.Unavailable), detail: null })),
    ).subscribe(result => {
      if (request !== this.generation) return;
      this.detail.set(result.detail);
      this.state.set(result.detail ? SaleSnapshotState.Found : result.resolution.state);
    });
  }

  retry(): void { if (this.requested) this.load(this.requested.wire); }

  loadPathSegment(rawSegment: unknown): void {
    const routeId = SaleRouteId.fromPathSegment(rawSegment);
    this.load(routeId?.wire ?? null);
  }

  private resolve(saleId: SaleRouteId, cursor: string | null, seen: ReadonlySet<string>, match: DurableSaleSummary | null): Observable<SaleRouteResolution> {
    let params = new HttpParams().set('limit', 100);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<unknown>(`${API_BASE}/sales`, { params }).pipe(switchMap(response => {
      const page = PosWireMapper.durablePage(response);
      if (!page) return throwError(() => new Error('invalid sale page'));
      const matches = page.items.filter(item => saleId.matches(item.identity.saleId));
      if (matches.length > 1 || (match && matches.length > 0)) return of(this.resolution(SaleSnapshotState.Ambiguous));
      const candidate = matches[0] ?? match;
      if (!page.nextCursor) return of(candidate
        ? this.resolution(SaleSnapshotState.Found, candidate.identity)
        : this.resolution(SaleSnapshotState.NotFound));
      if (seen.has(page.nextCursor)) return throwError(() => new Error('repeated sale cursor'));
      return this.resolve(saleId, page.nextCursor, new Set([...seen, page.nextCursor]), candidate);
    }));
  }

  private resolution(state: SaleSnapshotState, identity: TicketIdentity | null = null): SaleRouteResolution {
    return Object.freeze({ state, identity });
  }
}
