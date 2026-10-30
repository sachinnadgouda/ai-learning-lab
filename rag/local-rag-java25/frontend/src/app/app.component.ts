import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs';
import { RagApiService, IngestResult, QueryResult } from './rag-api.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  private readonly api = inject(RagApiService);
  readonly defaultUrl = 'https://phi-public.s3.amazonaws.com/recipes/ThaiRecipes.pdf';
  url = this.defaultUrl;
  questionRag = '';
  question = '';
  readonly ingesting = signal(false);
  readonly answeringRag = signal(false);
  readonly answering = signal(false);
  readonly ingestion = signal<IngestResult | null>(null);
  readonly result = signal<QueryResult | null>(null);
  readonly error = signal<string | null>(null);
  readonly sourcesOpen = signal(true);

  ingest(): void {
    if (this.ingesting() || !this.url.trim()) return;
    this.error.set(null);
    this.ingesting.set(true);
    this.api.ingest(this.url.trim()).pipe(finalize(() => this.ingesting.set(false)))
      .subscribe({ next: value => this.ingestion.set(value), error: error => this.error.set(this.errorMessage(error)) });
  }

  ask(): void {
    const question = this.questionRag.trim();
    if (this.answeringRag() || !question || question.length > 2000) return;
    this.error.set(null);
    this.result.set(null);
    this.answeringRag.set(true);
    this.api.ask(question).pipe(finalize(() => this.answeringRag.set(false)))
      .subscribe({ next: value => this.result.set(value), error: error => this.error.set(this.errorMessage(error)) });
  }

  askWithoutRag(): void {
    const question = this.question.trim();
    if (this.answering() || !question || question.length > 2000) return;
    this.error.set(null);
    this.result.set(null);
    this.answering.set(true);
    this.api.askWithoutRag(question).pipe(finalize(() => this.answering.set(false)))
        .subscribe({ next: value => this.result.set(value), error: error => this.error.set(this.errorMessage(error)) });
  }

  private errorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 0) return 'Cannot reach the backend. Check Docker container health.';
      return typeof error.error?.detail === 'string' ? error.error.detail : `Request failed (HTTP ${error.status}).`;
    }
    return 'Unexpected request error.';
  }
}
