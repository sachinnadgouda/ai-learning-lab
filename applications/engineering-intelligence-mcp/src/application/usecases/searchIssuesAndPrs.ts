import { InjectionWarning } from '../../domain/entities.js';
import { PromptInjectionGuard } from '../../domain/promptInjectionGuard.js';
import { GitHubPort } from '../ports.js';
import { SearchIssuesAndPrsInput } from '../schemas.js';

export interface SearchIssuesAndPrsResult {
  repository: string;
  filters: {
    state: 'open' | 'closed' | 'all';
    type: 'all' | 'issue' | 'pr';
    query: string | null;
    labels: string[];
  };
  pagination: {
    page: number;
    perPage: number;
    totalMatched: number;
    hasMore: boolean;
  };
  items: Array<{
    number: number;
    kind: 'issue' | 'pr';
    state: 'open' | 'closed';
    author: string;
    labels: string[];
    commentsCount: number;
    createdAt: string;
    updatedAt: string;
    url: string;
    fencedTitle: string;
    fencedBodyExcerpt: string;
  }>;
  injectionWarnings: InjectionWarning[];
}

export class SearchIssuesAndPrsUseCase {
  constructor(
    private readonly github: GitHubPort,
    private readonly injectionGuard: PromptInjectionGuard,
  ) {}

  public async execute(input: SearchIssuesAndPrsInput): Promise<SearchIssuesAndPrsResult> {
    const repository = `${input.owner}/${input.repo}`;
    const allItems = await this.github.listIssues(input.owner, input.repo, {
      state: input.state,
      labels: input.labels,
      limit: 100,
    });

    const normalizedQuery = input.query?.toLowerCase() ?? null;
    const filtered = allItems.filter((item) => {
      if (input.type === 'issue' && item.isPullRequest) return false;
      if (input.type === 'pr' && !item.isPullRequest) return false;
      if (input.labels && input.labels.length > 0) {
        const itemLabelSet = new Set(item.labels.map((l) => l.toLowerCase()));
        for (const label of input.labels) {
          if (!itemLabelSet.has(label.toLowerCase())) {
            return false;
          }
        }
      }
      if (normalizedQuery) {
        const haystack = `${item.title}\n${item.body}\n${item.labels.join(' ')}`.toLowerCase();
        if (!haystack.includes(normalizedQuery)) {
          return false;
        }
      }
      return true;
    });

    const start = (input.page - 1) * input.perPage;
    const slice = filtered.slice(start, start + input.perPage);
    const injectionWarnings: InjectionWarning[] = [];

    const items = slice.map((item) => {
      const titleCheck = this.injectionGuard.inspectAndFence(item.title, {
        source: `${repository}#${item.number}`,
        field: 'issue.title',
      });
      const bodyExcerpt = item.body.length > 400 ? `${item.body.slice(0, 400)}...` : item.body;
      const bodyCheck = this.injectionGuard.inspectAndFence(bodyExcerpt, {
        source: `${repository}#${item.number}`,
        field: 'issue.body',
      });
      injectionWarnings.push(...titleCheck.warnings, ...bodyCheck.warnings);

      return {
        number: item.number,
        kind: item.isPullRequest ? ('pr' as const) : ('issue' as const),
        state: item.state,
        author: item.author,
        labels: item.labels,
        commentsCount: item.commentsCount,
        createdAt: item.createdAt,
        updatedAt: item.updatedAt,
        url: item.url,
        fencedTitle: titleCheck.fenced,
        fencedBodyExcerpt: bodyCheck.fenced,
      };
    });

    return {
      repository,
      filters: {
        state: input.state,
        type: input.type,
        query: input.query ?? null,
        labels: input.labels ?? [],
      },
      pagination: {
        page: input.page,
        perPage: input.perPage,
        totalMatched: filtered.length,
        hasMore: start + input.perPage < filtered.length,
      },
      items,
      injectionWarnings,
    };
  }
}
