import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { AppComponent, needsReview } from './app.component';
import { Question, RagApiService } from './rag-api.service';

function question(overrides: Partial<Question> = {}): Question {
  return {
    id: 'q1', text: 'Annual leave?', askedAt: '2026-01-01T00:00:00Z', policyStatus: 'NO_MATCH',
    aiAnswer: null, sources: [], hrReply: null, answeredAt: null, ...overrides
  };
}

describe('AppComponent', () => {
  const api = {
    history: vi.fn(),
    allQuestions: vi.fn(),
    ask: vi.fn(),
    upload: vi.fn(),
    reply: vi.fn()
  };

  beforeEach(() => {
    Object.values(api).forEach(fn => fn.mockReset());
    api.history.mockReturnValue(of([]));
    api.allQuestions.mockReturnValue(of([]));
    TestBed.configureTestingModule({ providers: [{ provide: RagApiService, useValue: api }] });
  });

  it('only questions without an HR answer and without a policy answer need review', () => {
    expect(needsReview(question())).toBe(true);
    expect(needsReview(question({ policyStatus: 'MATCH_FOUND' }))).toBe(true);
    expect(needsReview(question({ policyStatus: 'MATCHED' }))).toBe(false);
    expect(needsReview(question({ policyStatus: 'NOT_CHECKED' }))).toBe(false);
    expect(needsReview(question({ answeredAt: '2026-01-02T00:00:00Z' }))).toBe(false);
  });

  it('adds a new question to history and clears the input', () => {
    const created = question({ policyStatus: 'MATCHED', aiAnswer: '25 days' });
    api.ask.mockReturnValue(of(created));
    const app = TestBed.createComponent(AppComponent).componentInstance;

    app.question.set('  Annual leave?  ');
    app.ask();

    expect(api.ask).toHaveBeenCalledWith('Annual leave?', true);
    expect(app.employeeQuestions()).toEqual([created]);
    expect(app.question()).toBe('');
  });

  it('ignores a stale employee response that arrives after switching to HR view', () => {
    const slowHistory = new Subject<Question[]>();
    api.history.mockReturnValue(slowHistory);
    api.allQuestions.mockReturnValue(of([question({ id: 'hr' })]));
    const app = TestBed.createComponent(AppComponent).componentInstance;

    app.switchView('hr');
    slowHistory.next([question({ id: 'employee' })]);

    expect(app.hrQuestions().map(q => q.id)).toEqual(['hr']);
    expect(app.employeeQuestions()).toEqual([]);
    expect(app.loading()).toBe(false);
  });

  it('filters the HR queue and removes the draft after a reply is saved', () => {
    const open = question({ id: 'open' });
    const matched = question({ id: 'matched', policyStatus: 'MATCHED' });
    api.allQuestions.mockReturnValue(of([open, matched]));
    api.reply.mockReturnValue(of(question({ id: 'open', hrReply: 'Yes', answeredAt: '2026-01-02T00:00:00Z' })));
    const app = TestBed.createComponent(AppComponent).componentInstance;
    app.switchView('hr');

    expect(app.visibleQuestions().map(q => q.id)).toEqual(['open']);
    app.setReply('open', ' Yes ');
    app.answer('open');

    expect(api.reply).toHaveBeenCalledWith('open', 'Yes');
    expect(app.replies()['open']).toBeUndefined();
    expect(app.visibleQuestions()).toEqual([]);
  });
});
