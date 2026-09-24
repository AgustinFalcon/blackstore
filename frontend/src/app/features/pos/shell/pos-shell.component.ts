import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'bs-pos-shell',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="page">
      <h2>Inicio</h2>
      <p class="lede">Consola de mostrador. El conector HTTP a StoreCore sigue bloqueado; caja y ticket usan el simulador.</p>
      <div class="card">
        <p>Sin sesión de caja abierta usá el atajo de caja. El ticket queda disponible; la reserva sigue en el simulador local.</p>
        <p class="actions">
          <a routerLink="/caja" class="primary">Abrir caja</a>
          <a routerLink="/ticket" class="ghost">Nuevo ticket</a>
          <a routerLink="/catalogo" class="ghost">Ver catálogo</a>
          <a routerLink="/reportes" class="ghost">Reportes</a>
        </p>
      </div>
    </section>
  `,
  styles: [
    `
      .actions {
        display: flex;
        gap: 0.75rem;
        flex-wrap: wrap;
      }
      a {
        display: inline-flex;
        align-items: center;
        text-decoration: none;
        box-sizing: border-box;
      }
    `,
  ],
})
export class PosShellComponent {}
