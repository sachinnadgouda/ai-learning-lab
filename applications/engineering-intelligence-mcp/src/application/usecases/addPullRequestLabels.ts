import { GitHubPort } from '../ports.js';
import { AddPullRequestLabelsInput } from '../schemas.js';

export interface AddPullRequestLabelsResult {
  repository: string;
  pullNumber: number;
  appliedLabels: string[];
}

export class AddPullRequestLabelsUseCase {
  constructor(private readonly github: GitHubPort) {}

  public async execute(
    input: AddPullRequestLabelsInput,
    options?: { signal?: AbortSignal },
  ): Promise<AddPullRequestLabelsResult> {
    const appliedLabels = await this.github.addLabelsToPullRequest(
      input.owner,
      input.repo,
      input.pullNumber,
      input.labels,
      { signal: options?.signal },
    );
    return {
      repository: `${input.owner}/${input.repo}`,
      pullNumber: input.pullNumber,
      appliedLabels,
    };
  }
}
