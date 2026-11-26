import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatCheckbox } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSelect, MatOption } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { AiInvocationDetail, AiInvocationSummary, InvocationComparison, PageResponse } from '../../core/models';
import { describeError } from '../../core/poller';
import { CopyButtonComponent } from '../../shared/copy-button.component';
import { JsonViewComponent } from '../../shared/json-view.component';
import { StateViewComponent } from '../../shared/state-view.component';

@Component({
  selector: 'app-ai-inspector',
  imports: [DatePipe, FormsModule, MatButton, MatCheckbox, MatFormFieldModule, MatInput, MatPaginator, MatSelect, MatOption,
    CopyButtonComponent, JsonViewComponent, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './ai-inspector.component.scss',
  templateUrl: './ai-inspector.component.html',
})
export class AiInspectorComponent {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly q = signal('');
  readonly decision = signal('');
  readonly validation = signal('');
  readonly status = signal('');
  readonly cycleId = signal('');
  readonly pageIndex = signal(0);

  readonly list = signal<PageResponse<AiInvocationSummary> | null>(null);
  readonly listLoading = signal(true);
  readonly listError = signal<string | null>(null);

  readonly selectedId = signal<string | null>(null);
  readonly detail = signal<AiInvocationDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<string | null>(null);

  readonly compareIds = signal<string[]>([]);
  readonly comparison = signal<InvocationComparison | null>(null);
  readonly compareError = signal<string | null>(null);

  readonly canCompare = computed(() => this.compareIds().length === 2);
  readonly validationErrors = computed(() => this.detail()?.independentValidation.errors ?? []);

  constructor() {
    this.loadList();
    const timer = setInterval(() => this.loadList(true), 10000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  loadList(silent = false): void {
    if (!silent) this.listLoading.set(true);
    this.api.invocations({
      page: this.pageIndex(), size: 15, q: this.q(), decision: this.decision(), validation: this.validation(),
      status: this.status(), cycleId: this.cycleId(),
    }).subscribe({
      next: (page) => {
        this.list.set(page);
        this.listError.set(null);
        this.listLoading.set(false);
        if (!this.selectedId() && page.items.length > 0) this.select(page.items[0].id);
      },
      error: (e: unknown) => {
        this.listError.set(describeError(e));
        this.listLoading.set(false);
      },
    });
  }

  applyFilters(): void {
    this.pageIndex.set(0);
    this.loadList();
  }

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.loadList();
  }

  select(id: string): void {
    this.selectedId.set(id);
    this.comparison.set(null);
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.api.invocation(id).subscribe({
      next: (d) => {
        if (this.selectedId() !== id) return;
        this.detail.set(d);
        this.detailLoading.set(false);
      },
      error: (e: unknown) => {
        this.detailError.set(describeError(e));
        this.detailLoading.set(false);
      },
    });
  }

  toggleCompare(id: string, checked: boolean): void {
    this.compareIds.update((ids) => {
      const next = checked ? [...ids.filter((x) => x !== id), id] : ids.filter((x) => x !== id);
      return next.slice(-2);
    });
  }

  runCompare(): void {
    const [left, right] = this.compareIds();
    this.compareError.set(null);
    this.api.compareInvocations(left, right).subscribe({
      next: (c) => this.comparison.set(c),
      error: (e: unknown) => this.compareError.set(describeError(e)),
    });
  }

  closeCompare(): void {
    this.comparison.set(null);
  }

  decisionClass(d: string | null): string {
    return d === 'PROPOSE' ? 'info' : d === 'NO_CHANGE' ? 'ok' : d === 'INSUFFICIENT_DATA' ? 'warn' : 'bad';
  }

  validationClass(v: string): string {
    return v === 'VALID' ? 'ok' : v === 'INVALID' ? 'bad' : 'warn';
  }

  evaluationJson(d: AiInvocationDetail): string {
    return d.independentValidation.evaluation ? JSON.stringify(d.independentValidation.evaluation) : '';
  }

  parsedJson(d: AiInvocationDetail): string {
    return JSON.stringify({
      decision: d.aiRecommendation.decision,
      admissionPerSecond: d.aiRecommendation.admissionPerSecond,
      maxQueue: d.aiRecommendation.maxQueue,
      rationale: d.aiRecommendation.rationale,
    });
  }
}
