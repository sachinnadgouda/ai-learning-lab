import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface UploadResult { documentId: string; chunks: number; matchesFound: number; recheckFailures: number; }
export interface SourceMatch { documentId: string; documentName: string; text: string; score: number; }
export type PolicyStatus = 'MATCHED' | 'NO_MATCH' | 'MATCH_FOUND' | 'NOT_CHECKED';
export interface Question {
  id: string;
  text: string;
  askedAt: string;
  policyStatus: PolicyStatus;
  aiAnswer: string | null;
  sources: SourceMatch[];
  hrReply: string | null;
  answeredAt: string | null;
}

const BROWSER_ID_KEY = 'hr-demo-browser-id';

@Injectable({ providedIn: 'root' })
export class RagApiService {
  private readonly http = inject(HttpClient);
  upload(file: File): Observable<UploadResult> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<UploadResult>('/api/v1/documents/pdf', form);
  }
  ask(question: string, searchPolicy: boolean): Observable<Question> {
    return this.http.post<Question>('/api/v1/questions', { question, searchPolicy }, this.browserOptions());
  }
  history(): Observable<Question[]> {
    return this.http.get<Question[]>('/api/v1/questions', this.browserOptions());
  }
  allQuestions(): Observable<Question[]> {
    return this.http.get<Question[]>('/api/v1/hr/questions');
  }
  reply(id: string, reply: string): Observable<Question> {
    return this.http.put<Question>(`/api/v1/hr/questions/${encodeURIComponent(id)}/reply`, { reply });
  }

  private browserOptions(): { headers: Record<string, string> } {
    let id = localStorage.getItem(BROWSER_ID_KEY);
    if (!id) {
      id = newUuid();
      localStorage.setItem(BROWSER_ID_KEY, id);
    }
    return { headers: { 'X-Demo-Browser-Id': id } };
  }
}

// crypto.randomUUID() exists only in secure contexts; the demo may be opened over plain HTTP on a LAN address.
function newUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
