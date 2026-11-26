export const STAGE_ICONS: Record<string, string> = {
  OBSERVED: 'visibility', TRIGGERED: 'bolt', AI_CALLED: 'smart_toy', PROPOSED: 'lightbulb', EVALUATED: 'fact_check',
  APPROVED: 'thumb_up', ACTIVATED: 'rocket_launch', NO_CHANGE: 'remove_circle_outline', INSUFFICIENT_DATA: 'help_outline',
  REJECTED: 'thumb_down', FAILED: 'error', ROLLED_BACK: 'undo', DIAGNOSTIC: 'info',
};

export function stageIcon(stage: string): string {
  return STAGE_ICONS[stage] ?? 'circle';
}

export function stageClass(stage: string): string {
  if (['ACTIVATED', 'APPROVED'].includes(stage)) return 'ok';
  if (['FAILED', 'REJECTED'].includes(stage)) return 'bad';
  if (['ROLLED_BACK', 'INSUFFICIENT_DATA', 'NO_CHANGE'].includes(stage)) return 'warn';
  return 'info';
}
