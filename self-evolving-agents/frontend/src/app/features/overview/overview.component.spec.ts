import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SUMMARY } from '../../testing/fixtures';
import { OverviewComponent } from './overview.component';

describe('OverviewComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  it('renders backend values, not placeholders', async () => {
    const fixture = TestBed.createComponent(OverviewComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/dashboard/summary').flush(SUMMARY);
    http.expectOne((r) => r.url === '/api/v1/traffic/timeseries').flush({ seconds: 300, stepSeconds: 5, source: 'OBSERVED', points: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="tiles"]')!.textContent).toContain('41.5');
    expect(el.querySelector('[data-testid="ai-status"]')!.textContent).toContain('Enabled');
    expect(el.querySelector('[data-testid="auto-status"]')!.textContent).toContain('Disabled');
    expect(el.querySelector('[data-testid="ollama-status"]')!.textContent).toContain('Connected');
    expect(el.textContent).toContain('No traffic observed yet');
  });

  it('shows an error state with retry when the backend fails', async () => {
    const fixture = TestBed.createComponent(OverviewComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/dashboard/summary').flush({ title: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    http.expectOne((r) => r.url === '/api/v1/traffic/timeseries').flush({}, { status: 503, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="error-state"]')).not.toBeNull();
  });
});
