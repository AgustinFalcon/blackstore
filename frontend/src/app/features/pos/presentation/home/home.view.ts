import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface HomeViewState {
  readonly loading: boolean;
  readonly signedIn: boolean;
  readonly denial: string | null;
  readonly catalogVersion: string;
  readonly catalogBadge: string;
  readonly catalogOk: boolean;
  readonly sessionText: string | null;
  readonly persistence: string;
  readonly blockReason: string | null;
  readonly ticketDisabled: boolean;
  readonly canCash: boolean;
  readonly canReports: boolean;
  readonly canClose: boolean;
  readonly error: string | null;
}

@Component({
  selector: 'bs-home-view',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.view.html',
})
export class HomeViewComponent {
  readonly state = input.required<HomeViewState>();
  readonly retry = output<void>();
}
