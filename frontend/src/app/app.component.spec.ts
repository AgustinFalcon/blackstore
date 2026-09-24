import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AppComponent } from './app.component';
import { HealthApiService } from './core/services/health-api.service';

describe('AppComponent', () => {
  it('keeps the StoreCore banner when health says integration is disabled', async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: HealthApiService,
          useValue: {
            getHealth: () =>
              of({
                code: 200,
                data: { service: 'blackstore', boundedContext: 'pos', storeCoreIntegrationEnabled: false },
                errorCode: null,
                retryable: null,
                message: null,
                traceId: 't-1',
              }),
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Integración StoreCore bloqueada');
  });

  it('shows the backend-down banner when health fails', async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: HealthApiService,
          useValue: {
            getHealth: () => throwError(() => new Error('down')),
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Backend no disponible');
  });
});
