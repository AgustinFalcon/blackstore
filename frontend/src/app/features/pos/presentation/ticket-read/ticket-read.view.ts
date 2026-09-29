import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { formatEfectivo } from '../../domain/money';
import { SaleSnapshot } from '../../domain/pos.models';
import { feesOf } from '../../domain/sale-block';

export interface TicketReadViewState {
  readonly loading: boolean;
  readonly missing: boolean;
  readonly error: string | null;
  readonly sale: SaleSnapshot | null;
  readonly saleId: string;
}

@Component({
  selector: 'bs-ticket-read-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ticket-read.view.html',
})
export class TicketReadViewComponent {
  readonly state = input.required<TicketReadViewState>();
  readonly retry = output<void>();
  readonly format = formatEfectivo;

  collected(sale: SaleSnapshot): number {
    return sale.payments
      .filter((payment) => payment.status.collected)
      .reduce((sum, payment) => sum + payment.amount, 0);
  }

  fees(sale: SaleSnapshot): number {
    return feesOf(sale.payments);
  }
}
