import { ChangeDetectionStrategy, Component, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { isSuccessResponse } from './core/models/base-response';
import { PosSessionStore } from './core/session/pos-session.store';
import { roleCapabilities } from './core/session/staff-role';
import { HealthApiService } from './core/services/health-api.service';
import { PosStore } from './features/pos/application/pos.store';
import { PosDialogComponent } from './features/pos/presentation/dialog/pos-dialog.component';

@Component({
  selector: 'bs-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, PosDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  private readonly healthApi = inject(HealthApiService);
  private readonly session = inject(PosSessionStore);
  private readonly router = inject(Router);
  readonly store = inject(PosStore);
  readonly showBlocked = signal(false);
  readonly caps = computed(() => roleCapabilities(this.session.actor()?.role ?? null));
  readonly roleLabel = computed(() => {
    const actor = this.session.actor();
    if (!actor) return 'Sin sesión · simulador local';
    return `${actor.role.label} · usuario ${actor.actorId} · simulador local`;
  });

  ngOnInit(): void {
    this.healthApi.getHealth().subscribe({
      next: (response) => {
        const enabled = isSuccessResponse(response) && response.data.storeCoreIntegrationEnabled;
        this.store.setHealth({ loading: false, backendDown: false, integrationBlocked: !enabled });
      },
      error: () => this.store.setHealth({ loading: false, backendDown: true, integrationBlocked: true }),
    });
  }

  @HostListener('window:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    if (event.key !== 'F2' && event.key !== 'F4') return;
    if (event.target instanceof HTMLTextAreaElement) return;
    event.preventDefault();
    if (event.key === 'F2' && this.caps().cash) void this.router.navigate(['/caja']);
    if (event.key === 'F4' && this.caps().sell && this.store.openSession()) void this.router.navigate(['/ticket']);
  }
}
