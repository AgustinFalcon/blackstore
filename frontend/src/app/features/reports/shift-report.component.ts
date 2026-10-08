import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
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

@Component({
  selector: 'bs-shift-report', standalone: true, imports: [FormsModule, AccountingReportPanelComponent], providers: [AccountingReportsStore],
  template: `
    <section class="page">
      <h2>Reportes y arqueo</h2>
      <p class="lede">Lecturas contables por turno y día. El backend verifica permisos, titular, terminal y zona autorizada en cada consulta.</p>
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
        @if (sessionsError()) { <p role="status">{{ sessionsError() }}</p><button type="button" (click)="loadSessions()">Actualizar cajas</button> }
        @if (store.shift.loading()) { <p role="status">Consultando turno…</p> }
        @if (store.shift.error(); as error) { <p role="alert">Turno: {{ error.label }}</p> }
        @if (store.shift.report(); as report) { <bs-accounting-report-panel [report]="report" /> }
      </section>
      <section aria-labelledby="day-heading" [attr.aria-busy]="store.day.loading()">
        <h3 id="day-heading">Día</h3>
        <form (ngSubmit)="loadDay()">
          <label>Fecha local <input name="localDate" type="date" [ngModel]="localDate" (ngModelChange)="localDate = $event; store.day.clear()" required /></label>
          <label>Zona IANA autorizada <input name="zone" [ngModel]="zone" (ngModelChange)="zone = $event; store.day.clear()" placeholder="America/Argentina/Buenos_Aires" required /></label>
          <fieldset>
            <legend>Filtros opcionales (el cajero es el titular de la caja)</legend>
            <label>Terminal <input name="terminalId" type="number" min="1" step="1" [ngModel]="terminalId" (ngModelChange)="terminalId = $event; store.day.clear()" /></label>
            <label>Titular <input name="cashierId" type="number" min="1" step="1" [ngModel]="cashierId" (ngModelChange)="cashierId = $event; store.day.clear()" /></label>
            <label>Caja <input name="daySessionId" type="number" min="1" step="1" [ngModel]="daySessionId" (ngModelChange)="daySessionId = $event; store.day.clear()" /></label>
          </fieldset>
          <button type="submit" [disabled]="!identity.can(permissions.DailyReportRead)">Consultar día</button>
        </form>
        @if (store.day.loading()) { <p role="status">Consultando día…</p> }
        @if (store.day.error(); as error) { <p role="alert">Día: {{ error.label }}</p> }
        @if (store.day.report(); as report) { <bs-accounting-report-panel [report]="report" /> }
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
      this.cancelSessions(); this.sessions.set([]); this.sessionsError.set(null); this.shiftSessionId = null;
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
    this.cancelSessions(); this.sessions.set([]); this.sessionsError.set(null); this.selectShift(null);
    if (!this.identity.can(StaffPermission.CashSessionList)) return;
    const generation = this.identity.generation(); const epoch = this.sessionsEpoch;
    const current = () => generation === this.identity.generation() && epoch === this.sessionsEpoch;
    this.pending = this.http.get<BaseResponse<CashSessionWire[]>>(`${API_BASE}/cash-sessions`).subscribe({
      next: response => {
        if (!current()) return;
        if (isSuccessResponse(response) && Array.isArray(response.data)) this.sessions.set(PosWireMapper.cashSessions(response.data));
        else this.sessionsError.set('No se pudo comprobar la lista de cajas visibles');
      },
      error: () => { if (current()) this.sessionsError.set('Lista de cajas temporalmente no disponible'); },
    });
  }
}
