import { AppError } from '../../domain/errors.js';
import { PermissionPolicy } from '../../domain/permissionPolicy.js';
import { GitHubPort, WriteReconciliationStorePort } from '../ports.js';
import { ReconcileWriteOutcomeInput } from '../schemas.js';

export interface ReconcileWriteOutcomeResult {
  correlationId: string;
  toolName: string;
  repository: string;
  previousStatus: 'AMBIGUOUS' | 'CONFIRMED_APPLIED' | 'NOT_FOUND_UPSTREAM';
  reconciledStatus: 'CONFIRMED_APPLIED' | 'NOT_FOUND_UPSTREAM';
  appliedUpstream: boolean;
  detail: string;
  recommendedAction: string;
  matchedResource?: {
    type: 'comment' | 'issue' | 'pull_request_labels';
    idOrNumber: number;
    url?: string;
    labels?: string[];
  };
}

export class ReconcileWriteOutcomeUseCase {
  constructor(
    private readonly github: GitHubPort,
    private readonly reconciliationStore: WriteReconciliationStorePort,
    private readonly permissionPolicy: PermissionPolicy,
  ) {}

  public async execute(
    input: ReconcileWriteOutcomeInput,
    options?: { signal?: AbortSignal },
  ): Promise<ReconcileWriteOutcomeResult> {
    const record = this.reconciliationStore.findWriteReconciliation(input.correlationId);
    if (!record) {
      throw new AppError(
        'NOT_FOUND',
        `No ambiguous write reconciliation record found for correlationId '${input.correlationId}'.`,
      );
    }

    const [owner = '', repo = ''] = record.repository.split('/');
    this.permissionPolicy.assertAllowed({
      toolName: 'read_reconcile_write_outcome',
      permissionClass: 'READ',
      owner,
      repo,
    });

    const previousStatus = record.status;
    const nowIso = new Date().toISOString();

    if (record.toolName === 'write_create_issue_comment') {
      if (record.targetNumber === null) {
        throw new AppError(
          'INTERNAL_ERROR',
          `Reconciliation record '${record.correlationId}' is missing target issue number.`,
        );
      }
      const comments = await this.github.listIssueComments(owner, repo, record.targetNumber, {
        limit: 100,
        signal: options?.signal,
      });
      const matched = comments.find((c) => c.body.includes(record.operationMarker));
      if (matched) {
        const detail = `Found comment #${matched.id} on ${record.repository}#${record.targetNumber} containing marker '${record.operationMarker}'.`;
        this.reconciliationStore.resolveWriteReconciliation(
          record.correlationId,
          'CONFIRMED_APPLIED',
          nowIso,
          detail,
        );
        return {
          correlationId: record.correlationId,
          toolName: record.toolName,
          repository: record.repository,
          previousStatus,
          reconciledStatus: 'CONFIRMED_APPLIED',
          appliedUpstream: true,
          detail,
          recommendedAction: 'Do NOT retry the write; the comment already exists on GitHub.',
          matchedResource: { type: 'comment', idOrNumber: matched.id, url: matched.url },
        };
      }

      const detail = `No comment containing marker '${record.operationMarker}' was found on ${record.repository}#${record.targetNumber}.`;
      this.reconciliationStore.resolveWriteReconciliation(
        record.correlationId,
        'NOT_FOUND_UPSTREAM',
        nowIso,
        detail,
      );
      return {
        correlationId: record.correlationId,
        toolName: record.toolName,
        repository: record.repository,
        previousStatus,
        reconciledStatus: 'NOT_FOUND_UPSTREAM',
        appliedUpstream: false,
        detail,
        recommendedAction:
          'The write was not observed on GitHub. You may safely start a fresh confirmed write request if still desired.',
      };
    }

    if (record.toolName === 'write_create_issue') {
      const issues = await this.github.listIssues(owner, repo, {
        state: 'all',
        limit: 100,
        signal: options?.signal,
      });
      const matched = issues.find(
        (issue) => !issue.isPullRequest && issue.body.includes(record.operationMarker),
      );
      if (matched) {
        const detail = `Found issue #${matched.number} ('${matched.title}') in ${record.repository} containing marker '${record.operationMarker}'.`;
        this.reconciliationStore.resolveWriteReconciliation(
          record.correlationId,
          'CONFIRMED_APPLIED',
          nowIso,
          detail,
        );
        return {
          correlationId: record.correlationId,
          toolName: record.toolName,
          repository: record.repository,
          previousStatus,
          reconciledStatus: 'CONFIRMED_APPLIED',
          appliedUpstream: true,
          detail,
          recommendedAction: 'Do NOT retry the write; the issue was already created on GitHub.',
          matchedResource: { type: 'issue', idOrNumber: matched.number, url: matched.url },
        };
      }

      const detail = `No issue containing marker '${record.operationMarker}' was found in ${record.repository}.`;
      this.reconciliationStore.resolveWriteReconciliation(
        record.correlationId,
        'NOT_FOUND_UPSTREAM',
        nowIso,
        detail,
      );
      return {
        correlationId: record.correlationId,
        toolName: record.toolName,
        repository: record.repository,
        previousStatus,
        reconciledStatus: 'NOT_FOUND_UPSTREAM',
        appliedUpstream: false,
        detail,
        recommendedAction:
          'The issue was not found on GitHub. You may safely start a fresh confirmed write request if still desired.',
      };
    }

    // write_add_pull_request_labels
    if (record.targetNumber === null) {
      throw new AppError(
        'INTERNAL_ERROR',
        `Reconciliation record '${record.correlationId}' is missing target pull request number.`,
      );
    }
    const pr = await this.github.getPullRequest(owner, repo, record.targetNumber, {
      signal: options?.signal,
    });
    const currentSet = new Set(pr.labels.map((l) => l.toLowerCase()));
    const missing = record.expectedLabels.filter((l) => !currentSet.has(l.toLowerCase()));

    if (missing.length === 0) {
      const detail = `All requested labels [${record.expectedLabels.join(', ')}] are present on ${record.repository}#${record.targetNumber}.`;
      this.reconciliationStore.resolveWriteReconciliation(
        record.correlationId,
        'CONFIRMED_APPLIED',
        nowIso,
        detail,
      );
      return {
        correlationId: record.correlationId,
        toolName: record.toolName,
        repository: record.repository,
        previousStatus,
        reconciledStatus: 'CONFIRMED_APPLIED',
        appliedUpstream: true,
        detail,
        recommendedAction: 'Do NOT retry; all requested labels are already applied.',
        matchedResource: {
          type: 'pull_request_labels',
          idOrNumber: pr.number,
          url: pr.url,
          labels: pr.labels,
        },
      };
    }

    const detail = `Labels [${missing.join(', ')}] are still missing from ${record.repository}#${record.targetNumber} (current labels: [${pr.labels.join(', ')}]).`;
    this.reconciliationStore.resolveWriteReconciliation(
      record.correlationId,
      'NOT_FOUND_UPSTREAM',
      nowIso,
      detail,
    );
    return {
      correlationId: record.correlationId,
      toolName: record.toolName,
      repository: record.repository,
      previousStatus,
      reconciledStatus: 'NOT_FOUND_UPSTREAM',
      appliedUpstream: false,
      detail,
      recommendedAction:
        'Not all requested labels were observed on GitHub. You may safely issue a fresh confirmed label write if still desired.',
      matchedResource: {
        type: 'pull_request_labels',
        idOrNumber: pr.number,
        url: pr.url,
        labels: pr.labels,
      },
    };
  }
}
