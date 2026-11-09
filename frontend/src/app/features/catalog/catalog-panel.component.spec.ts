import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../../core/api';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { CatalogPanelComponent } from './catalog-panel.component';

describe('CatalogPanelComponent actor isolation', () => {
  it('cancels the old request and never restores a previous actor projection', () => {
    TestBed.configureTestingModule({
      imports: [CatalogPanelComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const identity = TestBed.inject(SessionStore);
    authenticateTestSession(identity);
    const fixture = TestBed.createComponent(CatalogPanelComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const oldRequest = http.expectOne(`${API_BASE}/catalog`);

    identity.generation.update(value => value + 1);
    identity.changed.next();
    expect(oldRequest.cancelled).toBeTrue();
    expect(fixture.componentInstance.catalog()).toBeNull();

    fixture.componentInstance.load();
    http.expectOne(`${API_BASE}/catalog`).flush(envelope(catalog('new')));
    expect(fixture.componentInstance.catalog()?.items[0].sku).toBe('new');
    http.verify();
  });

  it('clears the previous projection while reloading and on errors', () => {
    TestBed.configureTestingModule({
      imports: [CatalogPanelComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    authenticateTestSession(TestBed.inject(SessionStore));
    const fixture = TestBed.createComponent(CatalogPanelComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(`${API_BASE}/catalog`).flush(envelope(catalog('first')));
    expect(fixture.componentInstance.catalog()).not.toBeNull();

    fixture.componentInstance.load();
    expect(fixture.componentInstance.catalog()).toBeNull();
    http.expectOne(`${API_BASE}/catalog`).flush(null, {status: 503, statusText: 'Unavailable'});
    expect(fixture.componentInstance.catalog()).toBeNull();
    expect(fixture.componentInstance.error()).toBe('Catálogo no disponible');
    http.verify();
  });
});

function catalog(sku: string) {
  return {version:'v1',source:'LOCAL_FIXTURE',stale:false,importedAt:null,validUntil:null,canonicalPath:'/blackstore-integration/v1',
    items:[{sku,name:'Producto',variantId:'variant',priceVersion:'p1',unitPrice:10}]};
}

function envelope(data: unknown) {
  return {code:200,traceId:'test',data,message:null,errorCode:null,retryable:null};
}
