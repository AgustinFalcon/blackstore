import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { API_BASE } from '../../core/api';
import { BaseResponse } from '../../core/models/base-response';

interface ShiftReportData {
  grossSales: number;
  discounts: number;
  netSales: number;
  refunds: number;
  collected: number;
  feesPaid: number;
  expensesPaid: number;
  operatingCashFlow: number;
  margin: number | null;
  formulaName: string;
  fiscalResult: boolean;
  periodKind: string;
}

@Component({
  selector: 'bs-shift-report',
  standalone: true,
  template: `
    <section>
      <h2>Reportes</h2>
      <p>Sin costo validado el margen es desconocido. La proyección no es resultado fiscal ni caja libre.</p>
      <button type="button" (click)="load()">Actualizar</button>
      @if (report(); as item) {
        <p>Turno · bruto {{ item.grossSales }} · descuentos {{ item.discounts }} · neto {{ item.netSales }}</p>
        <p>Cobrado {{ item.collected }} · reembolsos {{ item.refunds }} · comisiones {{ item.feesPaid }} · gastos {{ item.expensesPaid }}</p>
        <p>Caja operativa {{ item.operatingCashFlow }} · margen {{ item.margin === null ? 'desconocido' : item.margin }}</p>
        <p>{{ item.formulaName }} · {{ item.periodKind }} · fiscal {{ item.fiscalResult }}</p>
      }
      @if (daily(); as item) {
        <p>Día · {{ item.formulaName }} · {{ item.periodKind }} · neto {{ item.netSales }} · caja operativa {{ item.operatingCashFlow }} · fiscal {{ item.fiscalResult }}</p>
      }
      @if (error() && !report()) {
        <p>{{ error() }}</p>
      }
    </section>
  `,
})
export class ShiftReportComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly report = signal<ShiftReportData | null>(null);
  readonly daily = signal<ShiftReportData | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.http.get<BaseResponse<ShiftReportData>>(`${API_BASE}/reports/shift`).subscribe({
      next: (response) => this.report.set(response.data),
      error: () => this.error.set('Reporte no disponible'),
    });
    this.http.get<BaseResponse<ShiftReportData>>(`${API_BASE}/reports/daily`).subscribe({
      next: (response) => this.daily.set(response.data),
    });
  }
}
