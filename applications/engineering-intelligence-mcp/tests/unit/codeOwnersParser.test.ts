import { describe, expect, it } from 'vitest';
import { CodeOwnersParser } from '../../src/domain/codeOwnersParser.js';

describe('CodeOwnersParser', () => {
  const parser = new CodeOwnersParser();

  it('applies GitHub last-matching-rule-wins semantics across globs and anchored paths', () => {
    const content = [
      '# Global fallback',
      '* @org/default-team',
      '*.tf @org/infra-team',
      '/src/auth/ @org/security-team @alice',
      '.github/workflows/ @org/devex-team',
      'src/auth/public.ts @org/docs-team # inline comment',
    ].join('\n');

    const rules = parser.parse(content);
    const { matches, unownedFiles } = parser.matchFiles(
      [
        'README.md',
        'infra/network/vpc.tf',
        'src/auth/jwtVerifier.ts',
        'src/auth/public.ts',
        '.github/workflows/ci.yml',
      ],
      rules,
    );

    expect(unownedFiles).toHaveLength(0);
    const byPattern = new Map(matches.map((m) => [m.pattern, m]));
    expect(byPattern.get('*')?.matchedFiles).toEqual(['README.md']);
    expect(byPattern.get('*.tf')?.owners).toEqual(['@org/infra-team']);
    expect(byPattern.get('/src/auth/')?.matchedFiles).toEqual(['src/auth/jwtVerifier.ts']);
    expect(byPattern.get('src/auth/public.ts')?.owners).toEqual(['@org/docs-team']);
    expect(byPattern.get('.github/workflows/')?.matchedFiles).toEqual(['.github/workflows/ci.yml']);
  });

  it('reports unowned files when no fallback rule exists', () => {
    const rules = parser.parse('src/auth/ @org/security');
    const { matches, unownedFiles } = parser.matchFiles(
      ['src/auth/token.ts', 'src/billing/invoice.ts'],
      rules,
    );

    expect(matches).toHaveLength(1);
    expect(unownedFiles).toEqual(['src/billing/invoice.ts']);
  });
});
