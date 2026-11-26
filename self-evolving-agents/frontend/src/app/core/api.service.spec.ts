import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('uses relative /api/v1 URLs so the reverse proxy needs no configuration', () => {
    api.dashboard().subscribe();
    http.expectOne('/api/v1/dashboard/summary').flush({});
  });

  it('sends only non-empty invocation filters as query parameters', () => {
    api.invocations({ page: 2, size: 15, q: 'abc', decision: '', cycleId: undefined }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/ai/invocations');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('q')).toBe('abc');
    expect(req.request.params.has('decision')).toBe(false);
    expect(req.request.params.has('cycleId')).toBe(false);
    req.flush({ items: [], page: 2, size: 15, totalItems: 0, totalPages: 0 });
  });

  it('binds approval to the candidate fingerprint', () => {
    api.approve('2:EVOLVED:40:800:30', 'ok').subscribe();
    const req = http.expectOne('/api/v1/policies/candidate/approve');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ candidateFingerprint: '2:EVOLVED:40:800:30', reason: 'ok' });
    req.flush({});
  });

  it('writes flags with PUT', () => {
    api.setAi(false).subscribe();
    const req = http.expectOne('/api/v1/flags/ai');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({});
  });

  it('url-encodes ids', () => {
    api.invocation('a/b').subscribe();
    http.expectOne('/api/v1/ai/invocations/a%2Fb').flush({});
  });
});
