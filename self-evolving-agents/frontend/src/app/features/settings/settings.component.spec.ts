import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FLAGS, SUMMARY } from '../../testing/fixtures';
import { SettingsComponent } from './settings.component';

describe('SettingsComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(flags = FLAGS) {
    const fixture = TestBed.createComponent(SettingsComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/flags').flush(flags);
    http.expectOne('/api/v1/ai/status').flush({ flags, ollama: SUMMARY.ollama, approvalRequired: true, mode: 'advisory-only' });
    http.expectOne('/api/v1/system/settings').flush({ allowedModels: ['qwen2.5:3b'], restartRequired: { flags: false }, persistence: { flagsAndPolicies: 'Redis' } });
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('shows Ollama connectivity and runtime settings', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="ollama-connection"]')!.textContent).toContain('Connected');
    expect(el.querySelector('[data-testid="runtime-table"]')!.textContent).toContain('restartRequired');
  });

  it('turning AI off calls only the flag endpoint (no rollback)', async () => {
    const fixture = await render();
    fixture.componentInstance.apply(fixture.componentInstance['api'].setAi(false));
    const req = http.expectOne('/api/v1/flags/ai');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({ ...FLAGS, aiEnabled: false });
    http.expectNone('/api/v1/policies/rollback');
    http.expectOne('/api/v1/flags').flush({ ...FLAGS, aiEnabled: false });
    http.expectOne('/api/v1/ai/status').flush({ flags: FLAGS, ollama: SUMMARY.ollama, approvalRequired: true, mode: 'x' });
  });

  it('warns when automatic evolution is on while AI is off', async () => {
    const el = (await render({ ...FLAGS, aiEnabled: false, automaticEvolutionEnabled: true })).nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="auto-without-ai"]')).not.toBeNull();
  });
});
