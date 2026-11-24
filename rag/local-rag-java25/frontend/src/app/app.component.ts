import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, finalize } from 'rxjs';
import { PolicyStatus, Question, RagApiService, UploadResult } from './rag-api.service';

type View = 'employee' | 'hr';
type Filter = 'review' | 'all' | 'matched' | 'answered' | 'not-checked';

const MAX_PDF_BYTES = 10_000_000;

export function needsReview(question: Question): boolean {
  return !question.answeredAt && (question.policyStatus === 'NO_MATCH' || question.policyStatus === 'MATCH_FOUND');
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormsModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  private readonly api = inject(RagApiService);
  private readonly destroyRef = inject(DestroyRef);
  private refreshRequest?: Subscription;

  readonly view = signal<View>('employee');
  readonly filter = signal<Filter>('review');
  readonly employeeQuestions = signal<Question[]>([]);
  readonly hrQuestions = signal<Question[]>([]);
  readonly visibleQuestions = computed(() => {
    if (this.view() === 'employee') return this.employeeQuestions();
    return this.hrQuestions().filter(question => {
      switch (this.filter()) {
        case 'review': return needsReview(question);
        case 'matched': return question.policyStatus === 'MATCHED' || question.policyStatus === 'MATCH_FOUND';
        case 'answered': return !!question.answeredAt;
        case 'not-checked': return question.policyStatus === 'NOT_CHECKED';
        case 'all': return true;
      }
    });
  });
  readonly policyLabels: Record<PolicyStatus, string> = {
    MATCHED: 'Policy matched',
    NO_MATCH: 'No policy match',
    MATCH_FOUND: 'Policy match found after upload',
    NOT_CHECKED: 'Not checked'
  };
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly loading = signal(false);
  readonly answering = signal(false);
  readonly uploading = signal(false);
  readonly saving = signal(false);
  readonly busy = computed(() => this.answering() || this.uploading() || this.saving());
  readonly uploadResult = signal<UploadResult | null>(null);
  readonly question = signal('');
  readonly searchPolicy = signal(true);
  readonly pdf = signal<File | null>(null);
  readonly replies = signal<Record<string, string | undefined>>({});
  readonly needsReview = needsReview;

  constructor() {
    this.destroyRef.onDestroy(() => this.refreshRequest?.unsubscribe());
    this.refresh();
  }

  switchView(view: View): void {
    if (this.view() === view || this.busy()) return;
    this.view.set(view);
    this.error.set(null);
    this.notice.set(null);
    this.refresh();
  }

  refresh(): void {
    const view = this.view();
    const request = view === 'hr' ? this.api.allQuestions() : this.api.history();
    // A newer refresh (e.g. after switching views) supersedes any response still in flight.
    this.refreshRequest?.unsubscribe();
    this.loading.set(true);
    this.refreshRequest = request.subscribe({
      next: questions => (view === 'hr' ? this.hrQuestions : this.employeeQuestions).set(questions),
      error: error => {
        this.error.set(this.errorMessage(error));
        this.loading.set(false);
      },
      complete: () => this.loading.set(false)
    });
  }

  ask(): void {
    const text = this.question().trim();
    if (!text || this.busy() || this.loading()) return;
    this.error.set(null);
    this.notice.set(null);
    this.answering.set(true);
    this.api.ask(text, this.searchPolicy()).pipe(
      finalize(() => this.answering.set(false)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: response => {
        this.question.set('');
        this.employeeQuestions.update(questions => [response, ...questions]);
        this.notice.set(response.policyStatus === 'NO_MATCH'
          ? 'No matching policy was found. Your question was sent to HR for review.'
          : 'Answer added to your question history.');
      },
      error: error => this.error.set(this.errorMessage(error))
    });
  }

  selectPdf(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.pdf.set(event.target.files?.item(0) ?? null);
    this.uploadResult.set(null);
  }

  upload(input: HTMLInputElement): void {
    const file = this.pdf();
    if (!file || this.busy() || this.loading()) return;
    this.error.set(null);
    this.uploadResult.set(null);
    if (file.size > MAX_PDF_BYTES) {
      this.error.set('PDF exceeds the 10 MB upload limit.');
      return;
    }
    this.uploading.set(true);
    this.api.upload(file).pipe(
      finalize(() => this.uploading.set(false)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: response => {
        this.pdf.set(null);
        input.value = '';
        this.uploadResult.set(response);
        this.refresh();
      },
      error: error => this.error.set(this.errorMessage(error))
    });
  }

  setReply(id: string, text: string): void {
    this.replies.update(replies => ({ ...replies, [id]: text }));
  }

  answer(id: string): void {
    const reply = this.replies()[id]?.trim();
    if (!reply || this.busy() || this.loading()) return;
    this.error.set(null);
    this.notice.set(null);
    this.saving.set(true);
    this.api.reply(id, reply).pipe(
      finalize(() => this.saving.set(false)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: response => {
        this.replies.update(({ [id]: _sent, ...rest }) => rest);
        this.hrQuestions.update(questions => questions.map(question => question.id === id ? response : question));
        this.notice.set('HR reply saved and question marked answered.');
      },
      error: error => this.error.set(this.errorMessage(error))
    });
  }

  private errorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 0) return 'Cannot reach the backend. Check Docker container health.';
      if (typeof error.error?.detail === 'string') return error.error.detail;
      if (error.status === 413) return 'PDF exceeds the upload size limit.';
      return `Request failed (HTTP ${error.status}).`;
    }
    return 'Unexpected request error.';
  }
}
