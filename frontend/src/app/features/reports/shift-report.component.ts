import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionStore } from '../../core/services/session.store';
import { API_BASE } from '../../core/api';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
import { BaseResponse } from '../../core/models/base-response';
import { ShiftReportData, ShiftReportWire } from '../../core/models/pos-models';

@Component({
  selector: 'bs-shift-report',
  standalone: true,
  template: `
    <section class="page">
      <h2>Reportes</h2>
      <p class="lede">Sin costo validado el margen es desconocido. La proyección no es resultado fiscal ni caja libre.</p>
      <p class="retry-row">
        <button type="button" class="primary" (click)="load()">Actualizar</button>
      </p>
      @if (loading() && !report()) {
        <p class="skeleton" aria-hidden="true"></p>
      }
      @if (report(); as item) {
        <div class="metrics">
          <div class="card metric">
            <dt>Bruto turno</dt>
            <dd class="money">{{ item.grossSales }}</dd>
          </div>
          <div class="card metric">
            <dt>Descuentos</dt>
            <dd class="money">{{ item.discounts }}</dd>
          </div>
          <div class="card metric">
            <dt>Neto</dt>
            <dd class="money">{{ item.netSales }}</dd>
          </div>
          <div class="card metric">
            <dt>Cobrado</dt>
            <dd class="money">{{ item.collected }}</dd>
          </div>
          <div class="card metric">
            <dt>Reembolsos</dt>
            <dd class="money">{{ item.refunds }}</dd>
          </div>
          <div class="card metric">
            <dt>Comisiones</dt>
            <dd class="money">{{ item.feesPaid }}</dd>
          </div>
          <div class="card metric">
            <dt>Gastos</dt>
            <dd class="money">{{ item.expensesPaid }}</dd>
          </div>
          <div class="card metric">
            <dt>Caja operativa</dt>
            <dd class="money">{{ item.operatingCashFlow }}</dd>
          </div>
          <div class="card metric">
            <dt>Margen</dt>
            <dd>
              @if (item.margin === null) {
                <span class="badge warn">desconocido</span>
              } @else {
                <span class="money">{{ item.margin }}</span>
              }
            </dd>
          </div>
        </div>
        <p>{{ item.formulaName.label }} · {{ item.periodKind.label }} · {{ fiscalLabel(item.fiscalResult) }}</p>
      }
      @if (daily(); as item) {
        <div class="card">
          <p>Día · {{ item.formulaName.label }} · {{ item.periodKind.label }} · neto <span class="money">{{ item.netSales }}</span> · caja operativa <span class="money">{{ item.operatingCashFlow }}</span> · {{ fiscalLabel(item.fiscalResult) }}</p>
        </div>
      }
      @if (error() && !report()) {
        <div class="retry-row">
          <p>{{ error() }}</p>
          <button type="button" class="ghost" (click)="load()">Reintentar</button>
        </div>
      }
    </section>
  `,
})
export class ShiftReportComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly report = signal<ShiftReportData | null>(null);
  readonly daily = signal<ShiftReportData | null>(null);
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);

  constructor() {
    inject(SessionStore).changed.pipe(takeUntilDestroyed()).subscribe(() => {
      this.report.set(null); this.daily.set(null); this.error.set(null); this.loading.set(false);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  fiscalLabel(fiscalResult: boolean): string {
    return fiscalResult ? 'dato marcado, sin emisión' : 'no es resultado fiscal';
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.http.get<BaseResponse<ShiftReportWire>>(`${API_BASE}/reports/shift`).subscribe({
      next: (response) => {
        this.report.set(response.data ? PosWireMapper.report(response.data) : null);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Reporte no disponible');
        this.loading.set(false);
      },
    });
    this.http.get<BaseResponse<ShiftReportWire>>(`${API_BASE}/reports/daily`).subscribe({
      next: (response) => this.daily.set(response.data ? PosWireMapper.report(response.data) : null),
      error: () => this.daily.set(null),
    });
  }
}
