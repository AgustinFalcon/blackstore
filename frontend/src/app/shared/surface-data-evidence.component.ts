import { Component, Input } from '@angular/core';
import { SurfaceDataSource } from '../core/domain/data-provenance';

export interface SurfaceDatasetEvidence {
  readonly id: string;
  readonly label: string;
  readonly source: SurfaceDataSource;
}

@Component({
  selector: 'bs-surface-data-evidence',
  standalone: true,
  template: `
    <aside class="surface-source" [attr.data-surface-id]="surfaceId" [attr.data-source]="source.wire" aria-label="Procedencia de datos">
      <p><strong>Origen de datos:</strong> <span class="badge" [class.ok]="source.productionReady" [class.warn]="!source.productionReady">{{ source.label }}</span></p>
      <details>
        <summary>Ver fuentes observadas</summary>
        <ul>
          @for (dataset of datasets; track dataset.id) {
            <li [attr.data-dataset]="dataset.id" [attr.data-source]="dataset.source.wire">
              {{ dataset.label }}: <strong>{{ dataset.source.label }}</strong>
            </li>
          }
        </ul>
      </details>
    </aside>
  `,
  styles: [`
    :host { display: block; }
    .surface-source { margin: .75rem 0 1rem; padding: .75rem 1rem; border: 1px solid var(--bs-line); border-radius: .75rem; background: var(--bs-surface); }
    .surface-source p { margin: 0; }
    details { margin-top: .5rem; }
    ul { margin: .5rem 0 0; padding-left: 1.25rem; }
  `],
})
export class SurfaceDataEvidenceComponent {
  @Input({ required: true }) surfaceId = '';
  @Input({ required: true }) source = SurfaceDataSource.Unknown;
  @Input({ required: true }) datasets: readonly SurfaceDatasetEvidence[] = [];
}
