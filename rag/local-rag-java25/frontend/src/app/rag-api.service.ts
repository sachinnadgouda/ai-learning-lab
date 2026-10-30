import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface IngestResult { documentId: string; chunks: number; }
export interface SourceMatch { documentId: string; text: string; score: number; }
export interface QueryResult {
  answer: string;
  sources: SourceMatch[];
  grounded?: boolean;
  source?: 'rag' | 'internet';
  advisory?: string | null;
}

@Injectable({ providedIn: 'root' })
export class RagApiService {
  private readonly http = inject(HttpClient);
  ingest(url: string): Observable<IngestResult> {
    return this.http.post<IngestResult>('/api/v1/documents', { url });
  }
  ask(question: string): Observable<QueryResult> {
    return this.http.post<QueryResult>('/api/v1/questions', { question });
  }
  askWithoutRag(question: string): Observable<QueryResult> {
    return this.http.post<QueryResult>('/api/v1/questions/general', { question });
  }
}
