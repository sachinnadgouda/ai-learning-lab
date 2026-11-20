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
    return this.http.put<Question>(`/api/v1/hr/questions/${id}/reply`, { reply });
  }

  private browserOptions(): { headers: Record<string, string> } {
    const key = 'hr-demo-browser-id';
    let id = localStorage.getItem(key);
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem(key, id);
    }
    return { headers: { 'X-Demo-Browser-Id': id } };
  }
}
