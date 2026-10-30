import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RagApiService } from './rag-api.service';

describe('RagApiService', () => {
  let service: RagApiService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]});
    service = TestBed.inject(RagApiService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('posts ingestion requests', () => {
    service.ingest('https://example.org/a.pdf').subscribe(result => expect(result.chunks).toBe(2));
    const request = http.expectOne('/api/v1/documents');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({url: 'https://example.org/a.pdf'});
    request.flush({documentId: 'doc', chunks: 2});
  });
  it('posts questions and returns sources', () => {
    service.ask('Which curry?').subscribe(result => expect(result.sources).toHaveLength(1));
    const request = http.expectOne('/api/v1/questions');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({question: 'Which curry?'});
    request.flush({answer: 'Green curry', sources: [{documentId:'doc', text:'coconut', score:0.9}]});
  });
  it('posts general questions without RAG context', () => {
    service.askWithoutRag('What is annual leave?').subscribe(result => expect(result.source).toBe('internet'));
    const request = http.expectOne('/api/v1/questions/general');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({question: 'What is annual leave?'});
    request.flush({answer: 'General answer', sources: [], grounded: false, source: 'internet', advisory: 'Generated without RAG context; this is a general answer.'});
  });
});
