package domain

import (
	"fmt"
	"regexp"
	"strings"
)

// Sanitizer detects prompt injection payloads in untrusted event notes/carrier messages,
// fences untrusted data prior to any LLM call, and redacts sensitive secrets from logs.
type Sanitizer struct {
	injectionPatterns []*regexp.Regexp
	secretPatterns    []*regexp.Regexp
}

func NewSanitizer() *Sanitizer {
	return &Sanitizer{
		injectionPatterns: []*regexp.Regexp{
			regexp.MustCompile(`(?i)ignore\s+(all\s+)?(previous|prior|above)\s+(instructions|directives|prompts)`),
			regexp.MustCompile(`(?i)disregard\s+(all\s+)?(previous|prior|system)\s+(instructions|rules)`),
			regexp.MustCompile(`(?i)(reveal|print|dump|output)\s+(the\s+)?(system\s+prompt|api\s*key|secret)`),
			regexp.MustCompile(`(?i)(auto[- ]?approve|bypass\s+human\s+approval|execute\s+immediately|override\s+governance)`),
			regexp.MustCompile(`(?i)(you\s+are\s+now|act\s+as\s+system|new\s+system\s+instruction)`),
			regexp.MustCompile(`(?i)(<\|im_start\|>|<\|system\|>|###\s*system\b|</untrusted_data>)`),
		},
		secretPatterns: []*regexp.Regexp{
			regexp.MustCompile(`(?i)(bearer\s+)[a-z0-9._\-]{8,}`),
			regexp.MustCompile(`(?i)(x-api-key[:=]\s*)[a-z0-9._\-]{6,}`),
			regexp.MustCompile(`(?i)((?:api[_-]?key|token|secret|password)\s*[:=]\s*["']?)[^\s"',;]{6,}`),
			regexp.MustCompile(`\bsk-[A-Za-z0-9]{16,}\b`),
		},
	}
}

// DetectInjection inspects untrusted text and returns matched pattern strings.
func (s *Sanitizer) DetectInjection(text string) (bool, []string) {
	if strings.TrimSpace(text) == "" {
		return false, nil
	}
	matches := make([]string, 0)
	for _, re := range s.injectionPatterns {
		if m := re.FindString(text); m != "" {
			matches = append(matches, m)
		}
	}
	return len(matches) > 0, matches
}

// FenceUntrustedText neutralizes delimiter breakouts and control bytes and wraps text
// in explicit untrusted-data boundaries before passing to any LLM prompt.
func (s *Sanitizer) FenceUntrustedText(sourceLabel, raw string) (string, bool, []string) {
	detected, matches := s.DetectInjection(raw)

	// Strip non-printable control chars (except \n and \t) and escape fence breakouts.
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range raw {
		if r == '\n' || r == '\t' || r >= 32 {
			b.WriteRune(r)
		}
	}
	cleaned := strings.ReplaceAll(b.String(), "</untrusted_data>", "[escaped_tag]")
	cleaned = strings.ReplaceAll(cleaned, "<untrusted_data", "[escaped_tag")
	cleaned = s.RedactSecrets(cleaned)

	// Cap untrusted note length to prevent context stuffing.
	const maxLen = 1000
	if len(cleaned) > maxLen {
		cleaned = cleaned[:maxLen] + "...[truncated]"
	}

	safeLabel := strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			return r
		}
		return '_'
	}, sourceLabel)
	if safeLabel == "" {
		safeLabel = "external_event"
	}

	fenced := fmt.Sprintf("<untrusted_data source=\"%s\">\n%s\n</untrusted_data>", safeLabel, cleaned)
	return fenced, detected, matches
}

// RedactSecrets masks bearer tokens, API keys, and passwords from strings prior to logging or persistence.
func (s *Sanitizer) RedactSecrets(text string) string {
	out := text
	for i, re := range s.secretPatterns {
		if i == 3 {
			out = re.ReplaceAllString(out, "[REDACTED_KEY]")
		} else {
			out = re.ReplaceAllString(out, "${1}[REDACTED]")
		}
	}
	return out
}
