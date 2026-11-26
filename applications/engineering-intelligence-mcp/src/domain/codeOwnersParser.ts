import { CodeOwnerMatch } from './entities.js';

export interface CodeOwnerRule {
  pattern: string;
  owners: string[];
  regex: RegExp;
}

export class CodeOwnersParser {
  public parse(content: string | null | undefined): CodeOwnerRule[] {
    if (!content) {
      return [];
    }

    const rules: CodeOwnerRule[] = [];
    for (const rawLine of content.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (line.length === 0 || line.startsWith('#')) {
        continue;
      }

      const tokens = tokenizeCodeOwnersLine(line);
      if (tokens.length < 2) {
        continue;
      }

      const pattern = tokens[0]!;
      const owners = tokens.slice(1).filter((token) => !token.startsWith('#'));
      if (owners.length === 0) {
        continue;
      }

      rules.push({
        pattern,
        owners,
        regex: compileCodeOwnersPattern(pattern),
      });
    }

    return rules;
  }

  public matchFiles(
    files: string[],
    rules: CodeOwnerRule[],
  ): { matches: CodeOwnerMatch[]; unownedFiles: string[] } {
    const grouped = new Map<string, CodeOwnerMatch>();
    const unownedFiles: string[] = [];

    for (const rawFile of files) {
      const normalizedFile = rawFile.replace(/^\/+/, '');
      let winningRule: CodeOwnerRule | null = null;

      // GitHub CODEOWNERS semantics: last matching rule wins
      for (const rule of rules) {
        if (rule.regex.test(normalizedFile)) {
          winningRule = rule;
        }
      }

      if (!winningRule) {
        unownedFiles.push(normalizedFile);
        continue;
      }

      const existing = grouped.get(winningRule.pattern);
      if (existing) {
        existing.matchedFiles.push(normalizedFile);
      } else {
        grouped.set(winningRule.pattern, {
          pattern: winningRule.pattern,
          owners: [...winningRule.owners],
          matchedFiles: [normalizedFile],
        });
      }
    }

    return {
      matches: Array.from(grouped.values()),
      unownedFiles,
    };
  }
}

function tokenizeCodeOwnersLine(line: string): string[] {
  const tokens: string[] = [];
  let current = '';
  let escaping = false;

  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i]!;
    if (escaping) {
      current += ch;
      escaping = false;
      continue;
    }
    if (ch === '\\') {
      escaping = true;
      continue;
    }
    if (ch === '#') {
      if (current.length > 0) {
        tokens.push(current);
        current = '';
      }
      break;
    }
    if (/\s/.test(ch)) {
      if (current.length > 0) {
        tokens.push(current);
        current = '';
      }
      continue;
    }
    current += ch;
  }

  if (current.length > 0) {
    tokens.push(current);
  }

  return tokens;
}

function compileCodeOwnersPattern(rawPattern: string): RegExp {
  if (rawPattern === '*') {
    return /^.+$/;
  }

  const anchored = rawPattern.startsWith('/');
  const dirOnly = rawPattern.endsWith('/');
  let pattern = rawPattern;

  if (anchored) {
    pattern = pattern.slice(1);
  }
  if (dirOnly) {
    pattern = pattern.slice(0, -1);
  }

  const hasSlashInside = pattern.includes('/');
  const regexBody = globToRegexBody(pattern);

  if (anchored || hasSlashInside) {
    if (dirOnly) {
      return new RegExp(`^${regexBody}/.*$`);
    }
    return new RegExp(`^${regexBody}(?:/.*)?$`);
  }

  // Unanchored basename or directory segment match
  if (dirOnly) {
    return new RegExp(`(?:^|/)${regexBody}/.*$`);
  }
  return new RegExp(`(?:^|/)${regexBody}$`);
}

function globToRegexBody(glob: string): string {
  let result = '';
  for (let i = 0; i < glob.length; i += 1) {
    const ch = glob[i]!;
    if (ch === '*') {
      if (glob[i + 1] === '*') {
        if (glob[i + 2] === '/') {
          result += '(?:.*/)?';
          i += 2;
        } else {
          result += '.*';
          i += 1;
        }
      } else {
        result += '[^/]*';
      }
    } else if (ch === '?') {
      result += '[^/]';
    } else if (/[.+^${}()|[\]\\]/.test(ch)) {
      result += `\\${ch}`;
    } else {
      result += ch;
    }
  }
  return result;
}
