import { TicketMoney } from '../../core/domain/ticket-transition';
import { RecordedInstant } from '../../core/domain/recorded-instant';

/** Commercial formatting for existing sale projections; it does not derive missing facts. */
export class SalePresentation {
  private static readonly currency = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', currencyDisplay: 'code' });
  private constructor() {}

  static amount(value: TicketMoney | null): string {
    return value === null ? 'No disponible' : this.currency.format(Number(value.decimal));
  }

  static instant(value: RecordedInstant): string { return value.format(); }
}
