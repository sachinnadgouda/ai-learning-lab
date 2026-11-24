import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';
import { RagApiService } from './rag-api.service';

describe('RagApiService', () => {
  let service: RagApiService;
  let http: HttpTestingController;
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value)
    });
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]});
    service = TestBed.inject(RagApiService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    http.verify();
  });
  it('uploads the policy PDF as multipart data', () => {
    const file = new File(['pdf'], 'policy.pdf', { type: 'application/pdf' });
    service.upload(file).subscribe(result => expect(result.chunks).toBe(2));
    const request = http.expectOne('/api/v1/documents/pdf');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.get('file')).toEqual(file);
    request.flush({documentId: 'doc', chunks: 2, matchesFound: 1, recheckFailures: 0});
  });
  it.each([true, false])('uses one question endpoint (search policy: %s)', searchPolicy => {
    service.ask('Annual leave?', searchPolicy).subscribe();
    const request = http.expectOne('/api/v1/questions');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({question: 'Annual leave?', searchPolicy});
    const browserId = request.request.headers.get('X-Demo-Browser-Id');
    expect(browserId).toBeTruthy();
    request.flush({});

    service.history().subscribe();
    const history = http.expectOne('/api/v1/questions');
    expect(history.request.method).toBe('GET');
    expect(history.request.headers.get('X-Demo-Browser-Id')).toBe(browserId);
    history.flush([]);
  });
  it('falls back to getRandomValues when randomUUID is unavailable (insecure context)', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(7) });
    service.history().subscribe();
    const request = http.expectOne('/api/v1/questions');
    expect(request.request.headers.get('X-Demo-Browser-Id')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    request.flush([]);
  });
  it('saves an HR reply', () => {
    service.reply('question-id', 'You receive 25 days.').subscribe();
    const request = http.expectOne('/api/v1/hr/questions/question-id/reply');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({reply: 'You receive 25 days.'});
    request.flush({});
  });
});
