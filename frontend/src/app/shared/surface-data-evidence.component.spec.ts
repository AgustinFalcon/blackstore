import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SurfaceDataSource } from '../core/domain/data-provenance';
import { SurfaceDataEvidenceComponent } from './surface-data-evidence.component';

describe('SurfaceDataEvidenceComponent', () => {
  let fixture: ComponentFixture<SurfaceDataEvidenceComponent>;

  beforeEach(() => {
    fixture = TestBed.createComponent(SurfaceDataEvidenceComponent);
    fixture.componentRef.setInput('surfaceId', 'BS-S02');
    fixture.componentRef.setInput('source', SurfaceDataSource.Mixed);
    fixture.componentRef.setInput('datasets', [
      {id:'workspace-context', label:'Puesto', source:SurfaceDataSource.Real},
      {id:'catalog-summary', label:'Catálogo', source:SurfaceDataSource.Fixture},
    ]);
    fixture.detectChanges();
  });

  it('publishes surface identity, aggregate and each observed dataset source', () => {
    const host = fixture.nativeElement as HTMLElement;
    const surface = host.querySelector('[data-surface-id="BS-S02"]');
    expect(surface?.getAttribute('data-source')).toBe('MIXED');
    expect(surface?.querySelector('[data-dataset="workspace-context"]')?.getAttribute('data-source')).toBe('REAL');
    expect(surface?.querySelector('[data-dataset="catalog-summary"]')?.getAttribute('data-source')).toBe('FIXTURE');
  });
});
