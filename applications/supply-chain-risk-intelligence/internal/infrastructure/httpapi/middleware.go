package httpapi

import (
	"context"
	"crypto/subtle"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
)

type ctxKey string

const (
	ctxKeyCorrelationID ctxKey = "correlation_id"
	ctxKeyPrincipal     ctxKey = "principal"
)

type Role string

const (
	RoleOperator  Role = "operator"
	RoleApprover  Role = "approver"
	RoleAdmin     Role = "admin"
	RoleAIService Role = "ai_service"
)

type Principal struct {
	Actor     string
	ActorType domain.ActorType
	Role      Role
}

type keyCredential struct {
	key       string
	principal Principal
}

func CorrelationIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(ctxKeyCorrelationID).(string); ok {
		return v
	}
	return ""
}

func PrincipalFromContext(ctx context.Context) (Principal, bool) {
	p, ok := ctx.Value(ctxKeyPrincipal).(Principal)
	return p, ok
}

// IdentityAuditSink optionally records rejected identity-override attempts in the durable audit log.
type IdentityAuditSink interface {
	InsertAudit(ctx context.Context, entry domain.AuditEntry) error
}

// KeyAuthenticator enforces server-side X-API-Key authentication and derives Actor + ActorType + Role
// strictly from trusted credential mappings. Client-supplied X-Actor-ID / X-Actor-Type headers are rejected.
type KeyAuthenticator struct {
	mu          sync.RWMutex
	credentials []keyCredential
	auditSink   IdentityAuditSink
}

func NewKeyAuthenticator(operatorKey, approverKey, adminKey string) *KeyAuthenticator {
	auth := &KeyAuthenticator{}
	if adminKey != "" {
		auth.AddCredential(adminKey, Principal{
			Actor:     "admin@supply-chain",
			ActorType: domain.ActorHuman,
			Role:      RoleAdmin,
		})
	}
	if approverKey != "" {
		auth.AddCredential(approverKey, Principal{
			Actor:     "approver@supply-chain",
			ActorType: domain.ActorHuman,
			Role:      RoleApprover,
		})
	}
	if operatorKey != "" {
		auth.AddCredential(operatorKey, Principal{
			Actor:     "operator@supply-chain",
			ActorType: domain.ActorHuman,
			Role:      RoleOperator,
		})
	}
	return auth
}

// WithAIServiceKey registers a dedicated non-human AI service credential bound to ActorAISystem.
func (a *KeyAuthenticator) WithAIServiceKey(aiKey string) *KeyAuthenticator {
	if strings.TrimSpace(aiKey) != "" {
		a.AddCredential(aiKey, Principal{
			Actor:     "ai-advisor@supply-chain",
			ActorType: domain.ActorAISystem,
			Role:      RoleAIService,
		})
	}
	return a
}

// WithAuditSink attaches a durable audit repository for recording unauthorized identity-override attempts.
func (a *KeyAuthenticator) WithAuditSink(sink IdentityAuditSink) *KeyAuthenticator {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.auditSink = sink
	return a
}

// AddCredential registers a trusted server-side API-key-to-Principal mapping.
func (a *KeyAuthenticator) AddCredential(apiKey string, p Principal) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.credentials = append(a.credentials, keyCredential{key: apiKey, principal: p})
}

func (a *KeyAuthenticator) Authenticate(r *http.Request) (Principal, bool) {
	key := strings.TrimSpace(r.Header.Get("X-API-Key"))
	if key == "" {
		return Principal{}, false
	}

	a.mu.RLock()
	creds := make([]keyCredential, len(a.credentials))
	copy(creds, a.credentials)
	a.mu.RUnlock()

	for _, c := range creds {
		if constantTimeMatch(key, c.key) {
			return c.principal, true
		}
	}
	return Principal{}, false
}

func hasIdentityOverrideHeaders(r *http.Request) (bool, map[string]any) {
	actorHdr := strings.TrimSpace(r.Header.Get("X-Actor-ID"))
	typeHdr := strings.TrimSpace(r.Header.Get("X-Actor-Type"))
	if actorHdr == "" && typeHdr == "" {
		return false, nil
	}
	details := map[string]any{}
	if actorHdr != "" {
		details["attempted_actor_id"] = actorHdr
	}
	if typeHdr != "" {
		details["attempted_actor_type"] = typeHdr
	}
	return true, details
}

func constantTimeMatch(a, b string) bool {
	if len(a) == 0 || len(b) == 0 || len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// RequireRoles wraps a handler with X-API-Key auth, identity-override rejection, and role verification.
func (a *KeyAuthenticator) RequireRoles(allowed ...Role) func(http.HandlerFunc) http.HandlerFunc {
	return func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			principal, ok := a.Authenticate(r)
			if !ok {
				writeProblem(w, r, http.StatusUnauthorized, "unauthorized", "Unauthorized", "Valid X-API-Key header is required.")
				return
			}
			// Never trust client-supplied X-Actor-ID or X-Actor-Type headers for audit identity.
			if overridden, details := hasIdentityOverrideHeaders(r); overridden {
				a.mu.RLock()
				sink := a.auditSink
				a.mu.RUnlock()
				if sink != nil {
					details["path"] = r.URL.Path
					details["method"] = r.Method
					_ = sink.InsertAudit(r.Context(), domain.AuditEntry{
						ID:            "aud-" + uuid.NewString(),
						CorrelationID: CorrelationIDFromContext(r.Context()),
						Actor:         principal.Actor,
						ActorType:     principal.ActorType,
						Action:        "IDENTITY_OVERRIDE_REJECTED",
						ResourceType:  "AUTH",
						ResourceID:    r.URL.Path,
						Outcome:       "FORBIDDEN",
						Details:       details,
						CreatedAt:     time.Now().UTC(),
					})
				}
				writeProblem(
					w,
					r,
					http.StatusForbidden,
					"identity-override-forbidden",
					"Unauthorized Identity Override",
					"Client-supplied X-Actor-ID and X-Actor-Type headers are not permitted; actor identity is derived strictly from authenticated credentials.",
				)
				return
			}
			for _, role := range allowed {
				if principal.Role == role {
					ctx := context.WithValue(r.Context(), ctxKeyPrincipal, principal)
					next(w, r.WithContext(ctx))
					return
				}
			}
			writeProblem(w, r, http.StatusForbidden, "forbidden", "Forbidden", "Authenticated role lacks permission for this endpoint.")
		}
	}
}

// IPRateLimiter implements a thread-safe per-IP token bucket rate limiter.
type IPRateLimiter struct {
	mu      sync.Mutex
	rps     float64
	burst   float64
	buckets map[string]*tokenBucket
}

type tokenBucket struct {
	tokens   float64
	lastSeen time.Time
}

func NewIPRateLimiter(rps float64, burst int) *IPRateLimiter {
	if rps <= 0 {
		rps = 50
	}
	if burst < 1 {
		burst = 100
	}
	return &IPRateLimiter{
		rps:     rps,
		burst:   float64(burst),
		buckets: make(map[string]*tokenBucket),
	}
}

func (l *IPRateLimiter) Allow(ip string) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()

	// Periodic cleanup to keep memory bounded.
	if len(l.buckets) > 2048 {
		for k, b := range l.buckets {
			if now.Sub(b.lastSeen) > 5*time.Minute {
				delete(l.buckets, k)
			}
		}
	}

	b, exists := l.buckets[ip]
	if !exists {
		l.buckets[ip] = &tokenBucket{tokens: l.burst - 1, lastSeen: now}
		return true
	}
	elapsed := now.Sub(b.lastSeen).Seconds()
	b.lastSeen = now
	b.tokens += elapsed * l.rps
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	if b.tokens < 1.0 {
		return false
	}
	b.tokens -= 1.0
	return true
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (sr *statusRecorder) WriteHeader(code int) {
	sr.status = code
	sr.ResponseWriter.WriteHeader(code)
}

// WrapMiddleware applies Correlation-ID, narrow CORS, per-IP rate limiting, request metrics, and structured slog logging.
func WrapMiddleware(
	next http.Handler,
	allowedOrigins []string,
	limiter *IPRateLimiter,
	metrics *application.Metrics,
	logger *slog.Logger,
) http.Handler {
	originSet := make(map[string]struct{}, len(allowedOrigins))
	for _, o := range allowedOrigins {
		originSet[o] = struct{}{}
	}
	if logger == nil {
		logger = slog.Default()
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()

		corrID := strings.TrimSpace(r.Header.Get("X-Correlation-ID"))
		if corrID == "" || len(corrID) > 128 {
			corrID = "req-" + uuid.NewString()
		}
		ctx := context.WithValue(r.Context(), ctxKeyCorrelationID, corrID)
		r = r.WithContext(ctx)
		w.Header().Set("X-Correlation-ID", corrID)

		// Narrow CORS
		if origin := r.Header.Get("Origin"); origin != "" {
			if _, ok := originSet[origin]; ok {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-API-Key, X-Correlation-ID")
			}
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
		}

		// Rate limit non-health endpoints
		if limiter != nil && r.URL.Path != "/health" && r.URL.Path != "/ready" {
			if !limiter.Allow(clientIP(r)) {
				w.Header().Set("Retry-After", "1")
				writeProblem(w, r, http.StatusTooManyRequests, "rate-limited", "Too Many Requests", "Per-IP rate limit exceeded.")
				return
			}
		}

		// Cap request body size to 1MB to prevent memory abuse.
		r.Body = http.MaxBytesReader(w, r.Body, 1<<20)

		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)

		if metrics != nil {
			metrics.IncHTTPReq()
		}
		logger.Info("http_request",
			"correlation_id", corrID,
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}
