import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { policyState } from '../../testing/fixtures';
import { PoliciesComponent, diffOf } from './policies.component';
import { ACTIVE, CANDIDATE_POLICY } from '../../testing/fixtures';

describe('PoliciesComponent', () => {
  let http: HttpTestingController;
  let dialogResult: { reason: string } | undefined;

  beforeEach(() => {
    dialogResult = undefined;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(dialogResult) }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(evaluated: boolean, history: typeof ACTIVE[] = []) {
    const fixture = TestBed.createComponent(PoliciesComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/policies').flush({ ...policyState(evaluated), history });
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const button = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement;

  it('never allows approving an unevaluated candidate', async () => {
    const fixture = await render(false);
    const el = fixture.nativeElement as HTMLElement;
    expect(button(el, 'approve').disabled).toBe(true);
    expect(el.querySelector('[data-testid="not-evaluated"]')).not.toBeNull();
    fixture.componentInstance.approve({ policy: CANDIDATE_POLICY, baselineVersion: 1, source: 'AI', rationale: '', cycleId: null, createdAt: '' });
    http.expectNone('/api/v1/policies/candidate/approve');
  });

  it('enables approval once evaluated, and highlights the changed parameters', async () => {
    const fixture = await render(true);
    const el = fixture.nativeElement as HTMLElement;
    expect(button(el, 'approve').disabled).toBe(false);
    expect(el.querySelector('[data-testid="evaluation-outcome"]')!.textContent).toContain('PASSED');
    expect(el.querySelectorAll('td.changed').length).toBe(3);
  });

  it('does not call the API when the confirmation is cancelled', async () => {
    const fixture = await render(true);
    button(fixture.nativeElement, 'approve').click();
    http.expectNone('/api/v1/policies/candidate/approve');
  });

  it('approves the exact evaluated candidate fingerprint after confirmation', async () => {
    dialogResult = { reason: 'reviewed' };
    const fixture = await render(true);
    button(fixture.nativeElement, 'approve').click();
    const req = http.expectOne('/api/v1/policies/candidate/approve');
    expect(req.request.body).toEqual({ candidateFingerprint: '2:EVOLVED:40:800:30', reason: 'reviewed' });
    req.flush(CANDIDATE_POLICY);
    http.expectOne('/api/v1/policies').flush(policyState(false));
  });

  it('requires confirmation before rollback', async () => {
    const fixture = await render(true, [ACTIVE]);
    button(fixture.nativeElement, 'rollback').click();
    http.expectNone('/api/v1/policies/rollback');
    dialogResult = { reason: 'bad' };
    button(fixture.nativeElement, 'rollback').click();
    http.expectOne('/api/v1/policies/rollback').flush(ACTIVE);
  });

  it('computes side-by-side differences', () => {
    const rows = diffOf(ACTIVE, CANDIDATE_POLICY);
    expect(rows.filter((r) => r.changed).map((r) => r.name)).toEqual(['Mode', 'Admission per second', 'Max queue']);
  });
});
