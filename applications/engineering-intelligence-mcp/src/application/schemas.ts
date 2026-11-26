import { z } from 'zod';

const safeSlugRegex = /^[a-zA-Z0-9._-]{1,100}$/;

export const RepoIdentifierSchema = z.object({
  owner: z
    .string()
    .trim()
    .regex(safeSlugRegex, 'owner must contain only alphanumerics, dots, underscores, or hyphens (1-100 chars)'),
  repo: z
    .string()
    .trim()
    .regex(safeSlugRegex, 'repo must contain only alphanumerics, dots, underscores, or hyphens (1-100 chars)'),
});

export const AnalyzePullRequestInputSchema = RepoIdentifierSchema.extend({
  pullNumber: z.number().int().positive(),
  includeAiSummary: z.boolean().optional().default(true),
});

export const InvestigateCiFailuresInputSchema = RepoIdentifierSchema.extend({
  branch: z.string().trim().min(1).max(200).optional(),
  maxRuns: z.number().int().min(1).max(50).optional().default(15),
  includeAiSummary: z.boolean().optional().default(true),
});

export const SummarizeEngineeringActivityInputSchema = RepoIdentifierSchema.extend({
  days: z.number().int().min(1).max(90).optional().default(14),
});

export const ComputeRepoInsightsInputSchema = RepoIdentifierSchema.extend({
  windowDays: z.number().int().min(1).max(180).optional().default(30),
  stalePrDays: z.number().int().min(1).max(90).optional().default(7),
});

export const SearchIssuesAndPrsInputSchema = RepoIdentifierSchema.extend({
  state: z.enum(['open', 'closed', 'all']).optional().default('open'),
  type: z.enum(['all', 'issue', 'pr']).optional().default('all'),
  query: z.string().trim().max(200).optional(),
  labels: z.array(z.string().trim().min(1).max(100)).max(10).optional(),
  page: z.number().int().min(1).max(50).optional().default(1),
  perPage: z.number().int().min(1).max(50).optional().default(20),
});

export const CreateIssueCommentInputSchema = RepoIdentifierSchema.extend({
  issueNumber: z.number().int().positive(),
  body: z.string().trim().min(1).max(10000),
  confirmationToken: z.string().trim().min(1).max(256).optional(),
});

export const CreateIssueInputSchema = RepoIdentifierSchema.extend({
  title: z.string().trim().min(3).max(256),
  body: z.string().trim().min(1).max(10000),
  labels: z.array(z.string().trim().min(1).max(50)).max(10).optional().default([]),
  confirmationToken: z.string().trim().min(1).max(256).optional(),
});

export const AddPullRequestLabelsInputSchema = RepoIdentifierSchema.extend({
  pullNumber: z.number().int().positive(),
  labels: z.array(z.string().trim().min(1).max(50)).min(1).max(10),
  confirmationToken: z.string().trim().min(1).max(256).optional(),
});

export const ReconcileWriteOutcomeInputSchema = z.object({
  correlationId: z.string().trim().min(8).max(128),
});

export type AnalyzePullRequestInput = z.infer<typeof AnalyzePullRequestInputSchema>;
export type InvestigateCiFailuresInput = z.infer<typeof InvestigateCiFailuresInputSchema>;
export type SummarizeEngineeringActivityInput = z.infer<
  typeof SummarizeEngineeringActivityInputSchema
>;
export type ComputeRepoInsightsInput = z.infer<typeof ComputeRepoInsightsInputSchema>;
export type SearchIssuesAndPrsInput = z.infer<typeof SearchIssuesAndPrsInputSchema>;
export type CreateIssueCommentInput = z.infer<typeof CreateIssueCommentInputSchema>;
export type CreateIssueInput = z.infer<typeof CreateIssueInputSchema>;
export type AddPullRequestLabelsInput = z.infer<typeof AddPullRequestLabelsInputSchema>;
export type ReconcileWriteOutcomeInput = z.infer<typeof ReconcileWriteOutcomeInputSchema>;
