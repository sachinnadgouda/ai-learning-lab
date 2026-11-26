import { IssueComment } from '../../domain/entities.js';
import { GitHubPort } from '../ports.js';
import { CreateIssueCommentInput } from '../schemas.js';

export interface CreateIssueCommentResult {
  repository: string;
  issueNumber: number;
  comment: IssueComment;
  operationMarker?: string;
}

export function formatOperationMarker(correlationId: string): string {
  return `<!-- mcp-op:${correlationId} -->`;
}

export class CreateIssueCommentUseCase {
  constructor(private readonly github: GitHubPort) {}

  public async execute(
    input: CreateIssueCommentInput,
    options?: { signal?: AbortSignal; correlationId?: string },
  ): Promise<CreateIssueCommentResult> {
    const marker = options?.correlationId
      ? formatOperationMarker(options.correlationId)
      : undefined;
    const bodyWithMarker =
      marker && !input.body.includes(marker) ? `${input.body}\n\n${marker}` : input.body;

    const comment = await this.github.createIssueComment(
      input.owner,
      input.repo,
      input.issueNumber,
      bodyWithMarker,
      { signal: options?.signal },
    );
    return {
      repository: `${input.owner}/${input.repo}`,
      issueNumber: input.issueNumber,
      comment,
      ...(marker ? { operationMarker: marker } : {}),
    };
  }
}
