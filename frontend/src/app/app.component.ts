import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

@Component({
  selector: 'bs-root',
  standalone: true,
  imports: [RouterLink, RouterOutlet],
  template: `
    <nav>
      <a routerLink="/">Estado</a>
      <a routerLink="/caja">Caja</a>
      <a routerLink="/catalogo">Catálogo</a>
      <a routerLink="/ticket">Ticket</a>
      <a routerLink="/reportes">Reportes</a>
    </nav>
    <router-outlet />
  `,
  styles: [
    `
      nav {
        display: flex;
        gap: 1rem;
        padding: 1rem 2rem 0;
      }
      a {
        color: inherit;
      }
    `,
  ],
})
export class AppComponent {}
