import { InjectionWarning } from './entities.js';

export interface SanitizedUntrustedContent {
  sanitized: string;
  fenced: string;
  warnings: InjectionWarning[];
  injectionDetected: boolean;
}

interface DetectionRule {
  category: InjectionWarning['category'];
  severity: InjectionWarning['severity'];
  pattern: RegExp;
}

const UNICODE_OBFUSCATION_REGEX = /[\u200B-\u200F\u202A-\u202E\u2060\u2066-\u2069\uFEFF]/g;

const DETECTION_RULES: DetectionRule[] = [
  {
    category: 'INSTRUCTION_OVERRIDE',
    severity: 'high',
    pattern:
      /(?:ignore|disregard|forget|override|bypass)\s+(?:all\s+)?(?:previous|prior|above|system|safety|security)\s+(?:instructions?|rules?|prompts?|policies?|constraints?|directives?)/gi,
  },
  {
    category: 'INSTRUCTION_OVERRIDE',
    severity: 'high',
    pattern:
      /(?:you\s+are\s+now|act\s+as|switch\s+to)\s+(?:a\s+|an\s+)?(?:unrestricted|developer|admin|root|jailbroken)\s+(?:mode|assistant|agent)/gi,
  },
  {
    category: 'ROLE_SMUGGLING',
    severity: 'high',
    pattern:
      /(?:^|\n)\s*(?:system|assistant|developer)\s*:\s*(?:override|you\s+must|execute|ignore|reveal|call|new\s+instructions)/gim,
  },
  {
    category: 'ROLE_SMUGGLING',
    severity: 'high',
    pattern: /(?:<\|im_start\|>|<\|im_end\|>|<<SYS>>|<\/SYS>|\[INST\]|\[\/INST\])/gi,
  },
  {
    category: 'TOOL_SMUGGLING',
    severity: 'high',
    pattern:
      /(?:call|invoke|execute|run|trigger)\s+(?:the\s+)?(?:tool\s+)?(?:write_[a-z0-9_]+|create_issue|create_issue_comment|add_pull_request_labels|mcp\s+tool)\b[^\n]{0,120}/gi,
  },
  {
    category: 'TOOL_SMUGGLING',
    severity: 'high',
    pattern:
      /(?:automatically\s+approve|auto-approve|skip\s+confirmation|bypass\s+confirmation|confirm\s+without\s+asking|set\s+risk\s*score\s*(?:to|=)\s*0)/gi,
  },
  {
    category: 'SECRET_EXFILTRATION',
    severity: 'high',
    pattern:
      /(?:print|reveal|dump|exfiltrate|send|post|leak|output|include)\s+(?:all\s+|the\s+|your\s+)?(?:GITHUB_TOKEN|API_KEY|CONFIRMATION_SECRET|secret(?:s)?|credentials?|environment\s+variables?|env\s+vars?|system\s+prompt)\b[^\n]{0,100}/gi,
  },
  {
    category: 'HIDDEN_DIRECTIVE',
    severity: 'medium',
    pattern:
      /<!--[\s\S]*?(?:ignore|instruction|system|tool|secret|token|exfiltrate|execute|approve|write_)[\s\S]*?-->/gi,
  },
];

export class PromptInjectionGuard {
  public inspectAndFence(
    rawText: string | null | undefined,
    metadata: { source: string; field: string },
  ): SanitizedUntrustedContent {
    const input = rawText ?? '';
    const warnings: InjectionWarning[] = [];
    let working = input;

    const unicodeMatches = working.match(UNICODE_OBFUSCATION_REGEX);
    if (unicodeMatches && unicodeMatches.length > 0) {
      warnings.push({
        field: metadata.field,
        category: 'UNICODE_OBFUSCATION',
        severity: 'medium',
        snippet: `Stripped ${unicodeMatches.length} invisible/bidirectional Unicode control character(s)`,
      });
      working = working.replace(UNICODE_OBFUSCATION_REGEX, '');
    }

    // Pass 1: Detect all matching injection vectors against the full normalized text
    // so earlier redactions never mask secondary attack categories (e.g. role smuggling or hidden comments).
    const snapshot = working;
    for (const rule of DETECTION_RULES) {
      rule.pattern.lastIndex = 0;
      const matches = snapshot.match(rule.pattern);
      if (matches && matches.length > 0) {
        for (const match of matches) {
          warnings.push({
            field: metadata.field,
            category: rule.category,
            severity: rule.severity,
            snippet: truncateSnippet(match.trim(), 120),
          });
        }
      }
    }

    // Pass 2: Sanitize container rules (hidden HTML comments) first, then specific directives
    const orderedForSanitization = [
      ...DETECTION_RULES.filter((r) => r.category === 'HIDDEN_DIRECTIVE'),
      ...DETECTION_RULES.filter((r) => r.category !== 'HIDDEN_DIRECTIVE'),
    ];
    for (const rule of orderedForSanitization) {
      rule.pattern.lastIndex = 0;
      working = working.replace(rule.pattern, '[REDACTED_UNTRUSTED_DIRECTIVE]');
    }

    // Prevent fence breakout if untrusted text embeds the closing tag
    const escapedForFence = working.replace(
      /<\/?untrusted_github_content[^>]*>/gi,
      '[ESCAPED_FENCE_TAG]',
    );

    const safeSource = sanitizeAttr(metadata.source);
    const safeField = sanitizeAttr(metadata.field);
    const fenced = `<untrusted_github_content source="${safeSource}" field="${safeField}">\n${escapedForFence}\n</untrusted_github_content>`;

    return {
      sanitized: working,
      fenced,
      warnings,
      injectionDetected: warnings.length > 0,
    };
  }

  public inspectText(
    rawText: string | null | undefined,
    field: string,
  ): { sanitized: string; warnings: InjectionWarning[]; injectionDetected: boolean } {
    const result = this.inspectAndFence(rawText, { source: 'github', field });
    return {
      sanitized: result.sanitized,
      warnings: result.warnings,
      injectionDetected: result.injectionDetected,
    };
  }
}

function truncateSnippet(value: string, maxLen: number): string {
  const singleLine = value.replace(/\s+/g, ' ');
  if (singleLine.length <= maxLen) {
    return singleLine;
  }
  return `${singleLine.slice(0, maxLen - 3)}...`;
}

function sanitizeAttr(value: string): string {
  return value.replace(/[^a-zA-Z0-9._:/-]/g, '_');
}
