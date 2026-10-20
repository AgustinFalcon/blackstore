import { Component, input } from '@angular/core';
import { AccountingReport } from '../../core/domain/accounting-report';
import { TicketMoney } from '../../core/domain/ticket-transition';

@Component({
  selector: 'bs-accounting-report-panel', standalone: true,
  template: `
    @if (report(); as item) {
      <article class="card" [attr.aria-label]="'Reporte de ' + item.period.label">
        <h3>Reporte de {{ item.period.label }} @if (item.cashSessionId) { · caja {{ item.cashSessionId }} }</h3>
        <p>{{ item.provisional ? 'Provisional al corte indicado' : 'Lectura histórica' }} · versión contable {{ item.accountingVersion }}</p>
        <dl>
          <dt>Desde (incluido)</dt><dd><time>{{ item.start }}</time></dd>
          <dt>Hasta (excluido)</dt><dd><time>{{ item.endExclusive }}</time></dd>
          <dt>Al corte / as-of</dt><dd><time>{{ item.cutoff }}</time></dd>
          <dt>Zona efectiva</dt><dd>{{ item.zone }} · {{ item.zoneVersion }} · desde {{ item.zoneEffectiveAt }}</dd>
          <dt>Snapshot consistente</dt><dd class="sku">{{ item.snapshot }}</dd>
        </dl>
        <p>Un importe no disponible significa falta de evidencia; no equivale a cero. Los importes parciales sólo incluyen fuentes acreditadas.</p>
        <dl class="metrics">
          <div class="metric"><dt>Ventas brutas reconocidas</dt><dd>{{ amount(item.grossSales) }}</dd></div>
          <div class="metric"><dt>Descuentos reconocidos</dt><dd>{{ amount(item.discounts) }}</dd></div>
          <div class="metric"><dt>{{ item.netSales.metric.label }}</dt><dd>{{ amount(item.netSales.value) }} · {{ item.netSales.coverage.state.label }}</dd></div>
        </dl>
        <h4>Cobertura por métrica</h4>
        <ul>
          @for (row of item.coverage; track row.metric) {
            <li>{{ row.metric.label }}: {{ row.coverage.state.label }}
              @for (cause of row.coverage.causes; track cause) { <span> · {{ cause.label }}</span> }
            </li>
          }
        </ul>
        <h4>Movimientos financieros por medio</h4>
        @for (row of item.totalsByMethod; track $index) {
          <section [attr.aria-label]="'Movimientos en ' + row.method.label">
            <h5>{{ row.method.label }}</h5>
            <dl class="metrics">
              @for (value of row.values; track value.metric) {
                <div class="metric"><dt>{{ value.metric.label }}</dt><dd>{{ amount(value.value) }} · {{ value.coverage.state.label }}</dd></div>
              }
              <div class="metric"><dt>Flujo operativo</dt><dd>{{ amount(row.operatingCashFlow) }}</dd></div>
            </dl>
          </section>
        }
        <p>El flujo financiero separa cobros, devoluciones, comisiones y egresos pagados. Apertura y devengo no son flujo operativo.</p>
        <h4>{{ item.formula.kind.label }} · {{ item.formula.version.label }}</h4>
        <p>{{ amount(item.formula.value) }} · {{ item.formula.coverage.state.label }}</p>
        @for (cause of item.formula.coverage.causes; track cause) { <p>{{ cause.label }}</p> }
        <p>Contribución = ventas netas − comisiones pagadas − egresos pagados. No representa utilidad fiscal, margen sin costo validado ni caja libre.</p>
        @if (item.reconciliation; as cash) {
          <h4>Arqueo · {{ cash.outcome.label }}</h4>
          <dl class="metrics">
            <div class="metric"><dt>Efectivo esperado</dt><dd>{{ amount(cash.expectedCash) }}</dd></div>
            <div class="metric"><dt>Efectivo declarado</dt><dd>{{ amount(cash.declaredCash) }}</dd></div>
            <div class="metric"><dt>Diferencia (declarado − esperado)</dt><dd>{{ amount(cash.difference) }}</dd></div>
          </dl>
          @if (cash.localWatermark !== null) { <p>Watermark de cierre: {{ cash.localWatermark }}</p> }
          <p>La diferencia no genera un ajuste automático. Una caja abierta aún no tiene declaración de cierre; un histórico sin cobertura conserva esperado y diferencia no disponibles.</p>
        } @else { <p>Este período no incluye un arqueo individual. El día no suma arqueos como movimientos.</p> }
      </article>
    }
  `,
})
export class AccountingReportPanelComponent {
  readonly report = input.required<AccountingReport>();
  amount(value: TicketMoney | null): string { return value?.decimal ?? 'No disponible'; }
}
