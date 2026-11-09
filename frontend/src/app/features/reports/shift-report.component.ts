import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { API_BASE } from '../../core/api';
import { AccountingReportRequest } from '../../core/domain/accounting-report';
import { StaffPermission } from '../../core/domain/session-types';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { BaseResponse, isSuccessResponse } from '../../core/models/base-response';
import { CashSessionData, CashSessionWire } from '../../core/models/pos-models';
import { AccountingReportsStore } from '../../core/services/accounting-reports.store';
import { SessionStore } from '../../core/services/session.store';
import { AccountingReportPanelComponent } from './accounting-report-panel.component';
import { SurfaceDataSource } from '../../core/domain/data-provenance';
import { SurfaceDataEvidenceComponent } from '../../shared/surface-data-evidence.component';

@Component({
  selector: 'bs-shift-report', standalone: true, imports: [FormsModule, AccountingReportPanelComponent, SurfaceDataEvidenceComponent], providers: [AccountingReportsStore],
  template: `
    <section class="page">
      <h2>Reportes y arqueo</h2>
      <p class="lede">Lecturas contables por turno y día. El backend verifica permisos, titular, terminal y zona autorizada en cada consulta.</p>
      <bs-surface-data-evidence surfaceId="BS-S09" [source]="surfaceSource()" [datasets]="surfaceDatasets()" />
      <div class="card"><span class="badge" [class.ok]="accountingSource().productionReady" [class.warn]="!accountingSource().productionReady">Contabilidad: {{ accountingSource().label }}</span> <span class="badge warn">Productos históricos: {{ historicalProductSource.label }}</span>
        <p>Los importes contables se leen de BlackStore. El origen histórico del catálogo no está persistido y se muestra como desconocido.</p></div>
      <section aria-labelledby="shift-heading" [attr.aria-busy]="store.shift.loading()">
        <h3 id="shift-heading">Turno</h3>
        <form (ngSubmit)="loadShift()">
          <label>Caja visible
            <select name="shiftSession" [ngModel]="shiftSessionId" (ngModelChange)="selectShift($event)" required>
              <option [ngValue]="null">Seleccioná una caja</option>
              @for (item of sessions(); track item.id) {
                <option [ngValue]="item.id">Caja {{ item.id }} · terminal {{ item.terminalId }} · titular {{ item.cashierId }} · {{ item.status.label }}</option>
              }
            </select>
          </label>
          <button type="submit" [disabled]="!identity.can(permissions.ShiftReportRead) || !shiftSessionId">Consultar turno</button>
        </form>
        @if (sessionsLoading()) { <p role="status">Consultando cajas visibles…</p> }
        @else if (sessionsError()) { <p role="status">{{ sessionsError() }}</p><button type="button" (click)="loadSessions()">Actualizar cajas</button> }
        @else if (!sessions().length) { <p class="empty">No hay cajas visibles para consultar.</p> }
        @else if (!shiftSessionId && !store.shift.report() && !store.shift.error()) { <p class="empty">Elegí una caja para consultar su turno.</p> }
        @if (store.shift.loading()) { <p role="status">Consultando turno…</p> }
        @if (store.shift.error(); as error) { <p role="alert">Turno: {{ error.label }}</p> }
        @if (store.shift.report(); as report) { <bs-accounting-report-panel [report]="report" /> }
      </section>
      <section aria-labelledby="day-heading" [attr.aria-busy]="store.day.loading()">
        <h3 id="day-heading">Día</h3>
        <p class="lede">Consultá el cierre comercial de una fecha. La zona debe coincidir con la autorizada para el negocio.</p>
        <form (ngSubmit)="loadDay()">
          <label>Fecha local <input name="localDate" type="date" [ngModel]="localDate" (ngModelChange)="localDate = $event; store.day.clear()" required aria-describedby="date-help" /></label><small id="date-help">Usá la fecha comercial del negocio.</small>
          <label>Zona IANA autorizada <input name="zone" list="business-zones" [ngModel]="zone" (ngModelChange)="zone = $event; store.day.clear()" placeholder="America/Argentina/Buenos_Aires" required aria-describedby="zone-help" /></label><small id="zone-help">La consulta usa la zona comercial autorizada, no la del dispositivo.</small>
          <datalist id="business-zones"><option value="America/Argentina/Buenos_Aires">Buenos Aires</option><option value="America/Argentina/Cordoba">Córdoba</option><option value="America/Argentina/Mendoza">Mendoza</option></datalist>
          <details class="advanced"><summary>Filtros técnicos avanzados</summary><fieldset>
            <legend>Acotar por identificadores autorizados</legend>
            <label>Terminal <input name="terminalId" type="number" min="1" step="1" [ngModel]="terminalId" (ngModelChange)="terminalId = $event; store.day.clear()" /></label>
            <label>Titular <input name="cashierId" type="number" min="1" step="1" [ngModel]="cashierId" (ngModelChange)="cashierId = $event; store.day.clear()" /></label>
            <label>Caja <input name="daySessionId" type="number" min="1" step="1" [ngModel]="daySessionId" (ngModelChange)="daySessionId = $event; store.day.clear()" /></label>
          </fieldset></details>
          <button type="submit" [disabled]="!identity.can(permissions.DailyReportRead) || !localDate || !zone.trim()">Consultar día</button>
        </form>
        @if (store.day.loading()) { <p role="status">Consultando día…</p> }
        @if (store.day.error(); as error) { <p role="alert">Día: {{ error.label }}</p> }
        @if (store.day.report(); as report) { <bs-accounting-report-panel [report]="report" /> }
        @else if (!store.day.loading() && !store.day.error()) { <p class="empty">Elegí fecha y zona para consultar el día comercial.</p> }
      </section>
    </section>
  `,
})
export class ShiftReportComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  readonly identity = inject(SessionStore);
  readonly permissions = StaffPermission;
  readonly store = inject(AccountingReportsStore);
  readonly sessions = signal<CashSessionData[]>([]);
  readonly sessionsError = signal<string | null>(null);
  readonly sessionsLoading = signal(false);
  readonly accountingSource = computed(() => {
    if (this.store.shift.report() || this.store.day.report()) return SurfaceDataSource.Real;
    if (this.store.shift.error() || this.store.day.error()) return SurfaceDataSource.Unavailable;
    return SurfaceDataSource.Unknown;
  });
  readonly historicalProductSource = SurfaceDataSource.Unknown;
  surfaceSource(): SurfaceDataSource { return SurfaceDataSource.aggregate([this.accountingSource(), this.historicalProductSource]); }
  surfaceDatasets() { return [
    {id:'accounting-ledger', label:'Contabilidad', source:this.accountingSource()},
    {id:'historical-product-provenance', label:'Origen histórico del producto', source:this.historicalProductSource},
  ] as const; }
  shiftSessionId: number | null = null;
  localDate = '';
  zone = '';
  terminalId: number | null = null;
  cashierId: number | null = null;
  daySessionId: number | null = null;
  private sessionsEpoch = 0;
  private pending: Subscription | null = null;
  constructor() {
    this.identity.changed.pipe(takeUntilDestroyed()).subscribe(() => {
      this.cancelSessions(); this.sessions.set([]); this.sessionsError.set(null); this.sessionsLoading.set(false); this.shiftSessionId = null;
      this.localDate = ''; this.zone = ''; this.terminalId = null; this.cashierId = null; this.daySessionId = null;
    });
  }
  ngOnInit(): void { this.loadSessions(); }
  ngOnDestroy(): void { this.cancelSessions(); this.store.clear(); }
  private cancelSessions(): void { this.sessionsEpoch++; this.pending?.unsubscribe(); this.pending = null; }
  selectShift(id: number | null): void { this.shiftSessionId = id; this.store.shift.clear(); }
  loadShift(): void {
    const selected = this.sessions().some(item => item.id === this.shiftSessionId);
    this.store.shift.load(selected ? AccountingReportRequest.shift(this.shiftSessionId) : null);
  }
  loadDay(): void {
    this.store.day.load(AccountingReportRequest.day(this.localDate, this.zone, {
      terminalId: this.terminalId, cashierId: this.cashierId, cashSessionId: this.daySessionId,
    }));
  }
  loadSessions(): void {
    this.cancelSessions(); this.sessions.set([]); this.sessionsError.set(null); this.sessionsLoading.set(true); this.selectShift(null);
    if (!this.identity.can(StaffPermission.CashSessionList)) { this.sessionsLoading.set(false); return; }
    const generation = this.identity.generation(); const epoch = this.sessionsEpoch;
    const current = () => generation === this.identity.generation() && epoch === this.sessionsEpoch;
    this.pending = this.http.get<BaseResponse<CashSessionWire[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: response => {
        if (!current()) return;
        if (isSuccessResponse(response) && Array.isArray(response.data)) this.sessions.set(PosWireMapper.cashSessions(response.data));
        else this.sessionsError.set('No se pudo comprobar la lista de cajas visibles');
        this.sessionsLoading.set(false);
      },
      error: () => { if (current()) { this.sessionsError.set('Lista de cajas temporalmente no disponible'); this.sessionsLoading.set(false); } },
    });
  }
}
