import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatEfectivo } from '../../domain/money';
import { CatalogSnapshot } from '../../domain/pos.models';

export interface CatalogViewState {
  readonly loading: boolean;
  readonly error: string | null;
  readonly catalog: CatalogSnapshot | null;
  readonly saleDisabled: boolean;
}

@Component({
  selector: 'bs-catalog-view',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './catalog.view.html',
})
export class CatalogViewComponent {
  readonly state = input.required<CatalogViewState>();
  readonly retry = output<void>();
  readonly format = formatEfectivo;
}
