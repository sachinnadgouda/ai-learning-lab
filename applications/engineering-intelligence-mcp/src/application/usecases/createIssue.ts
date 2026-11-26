import { Issue } from '../../domain/entities.js';
import { GitHubPort } from '../ports.js';
import { CreateIssueInput } from '../schemas.js';
import { formatOperationMarker } from './createIssueComment.js';

export interface CreateIssueResult {
  repository: string;
  issue: Issue;
  operationMarker?: string;
}

export class CreateIssueUseCase {
  constructor(private readonly github: GitHubPort) {}

  public async execute(
    input: CreateIssueInput,
    options?: { signal?: AbortSignal; correlationId?: string },
  ): Promise<CreateIssueResult> {
    const marker = options?.correlationId
      ? formatOperationMarker(options.correlationId)
      : undefined;
    const bodyWithMarker =
      marker && !input.body.includes(marker) ? `${input.body}\n\n${marker}` : input.body;

    const issue = await this.github.createIssue(
      input.owner,
      input.repo,
      {
        title: input.title,
        body: bodyWithMarker,
        labels: input.labels,
      },
      { signal: options?.signal },
    );
    return {
      repository: `${input.owner}/${input.repo}`,
      issue,
      ...(marker ? { operationMarker: marker } : {}),
    };
  }
}
