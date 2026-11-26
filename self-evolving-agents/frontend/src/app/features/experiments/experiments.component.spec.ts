import { rowsOf } from './experiments.component';
import { EVALUATION } from '../../testing/fixtures';

describe('experiment comparison rows', () => {
  it('marks improvements and covers all required dimensions', () => {
    const rows = rowsOf(EVALUATION.baseline, EVALUATION.candidate);
    expect(rows.map((r) => r.label).join('|')).toMatch(/Throughput.*latency.*error.*queue depth.*wait.*Rejections/s);
    expect(rows.find((r) => r.label.startsWith('Throughput'))!.better).toBe(true);
    expect(rows.find((r) => r.label === 'Rejections')!.better).toBe(true);
    expect(rows.find((r) => r.label.includes('latency'))!.better).toBeNull();
  });
});
