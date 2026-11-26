import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { INVOCATION, INVOCATION_SUMMARY } from '../../testing/fixtures';
import { AiInspectorComponent } from './ai-inspector.component';

describe('AiInspectorComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  async function render() {
    const fixture = TestBed.createComponent(AiInspectorComponent);
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/v1/ai/invocations').flush({ items: [INVOCATION_SUMMARY], page: 0, size: 15, totalItems: 1, totalPages: 1 });
    await fixture.whenStable();
    http.expectOne('/api/v1/ai/invocations/inv-12345678').flush(INVOCATION);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('shows the exact recorded prompt on the left and the raw response on the right', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    const left = el.querySelector('[data-testid="prompt-side"]')!.textContent!;
    const right = el.querySelector('[data-testid="response-side"]')!.textContent!;
    expect(left).toContain('You are a traffic policy advisor.');
    expect(left).toContain('Metrics: {"incomingRps":41.5}');
    expect(right).toContain('Arrivals exceed the limit');
    expect(el.querySelector('[data-testid="decision"]')!.textContent).toContain('PROPOSE');
    expect(el.textContent).toContain('inv-12345678');
  });

  it('distinguishes the AI recommendation from independent validation and lists failures', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="response-side"]')!.textContent).toContain('AI recommendation');
    const validation = el.querySelector('[data-testid="validation-side"]')!.textContent!;
    expect(validation).toContain('Independent validation');
    expect(validation).toContain('INVALID');
    expect(el.querySelector('[data-testid="validation-errors"]')!.textContent).toContain('admissionPerSecond above bound');
  });

  it('applies the search filter server-side', async () => {
    const fixture = await render();
    const component = fixture.componentInstance;
    component.q.set('needle');
    component.applyFilters();
    const req = http.expectOne((r) => r.url === '/api/v1/ai/invocations');
    expect(req.request.params.get('q')).toBe('needle');
    req.flush({ items: [], page: 0, size: 15, totalItems: 0, totalPages: 0 });
  });

  it('requires exactly two selections to compare', async () => {
    const fixture = await render();
    const component = fixture.componentInstance;
    component.toggleCompare('a', true);
    expect(component.canCompare()).toBe(false);
    component.toggleCompare('b', true);
    expect(component.canCompare()).toBe(true);
    component.runCompare();
    const req = http.expectOne((r) => r.url === '/api/v1/ai/invocations/compare');
    expect(req.request.params.get('left')).toBe('a');
    expect(req.request.params.get('right')).toBe('b');
    req.flush({ left: INVOCATION, right: INVOCATION, differences: [{ field: 'decision', left: 'A', right: 'B', different: true }] });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="comparison"]')).not.toBeNull();
  });

  it('shows an empty state when nothing was recorded', async () => {
    const fixture = TestBed.createComponent(AiInspectorComponent);
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/v1/ai/invocations').flush({ items: [], page: 0, size: 15, totalItems: 0, totalPages: 0 });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No AI invocations recorded yet.');
  });
});
