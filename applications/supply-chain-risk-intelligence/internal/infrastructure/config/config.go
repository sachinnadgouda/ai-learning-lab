package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Host string
	Port int

	DatabaseURL string

	EventBroker string // "memory" or "redis"
	RedisAddr   string
	RedisStream string
	RedisGroup  string

	QueueCapacity int
	WorkerCount   int
	MaxRetries    int
	RetryBase     time.Duration
	RetryMax      time.Duration

	AIProvider   string // "none", "ollama", "openai"
	AIBaseURL    string
	AIModel      string
	AIAPIKey     string
	AITimeout    time.Duration
	AIMaxRetries int

	AlertWebhookURL string

	APIKeyOperator  string
	APIKeyApprover  string
	APIKeyAdmin     string
	APIKeyAIService string

	CORSAllowedOrigins []string
	RateLimitRPS       float64
	RateLimitBurst     int
	LogLevel           string
}

func LoadFromEnv() (Config, error) {
	cfg := Config{
		Host:            envString("HOST", "127.0.0.1"),
		Port:            envInt("PORT", 8080),
		DatabaseURL:     envString("DATABASE_URL", "./data/supply_chain.db"),
		EventBroker:     strings.ToLower(envString("EVENT_BROKER", "memory")),
		RedisAddr:       envString("REDIS_ADDR", "127.0.0.1:6379"),
		RedisStream:     envString("REDIS_STREAM", "supply-chain-events"),
		RedisGroup:      envString("REDIS_GROUP", "risk-workers"),
		QueueCapacity:   envInt("QUEUE_CAPACITY", 256),
		WorkerCount:     envInt("WORKER_COUNT", 4),
		MaxRetries:      envInt("MAX_RETRIES", 3),
		RetryBase:       time.Duration(envInt("RETRY_BASE_MS", 50)) * time.Millisecond,
		RetryMax:        time.Duration(envInt("RETRY_MAX_MS", 2000)) * time.Millisecond,
		AIProvider:      strings.ToLower(envString("AI_PROVIDER", "none")),
		AIBaseURL:       envString("AI_BASE_URL", ""),
		AIModel:         envString("AI_MODEL", ""),
		AIAPIKey:        envString("AI_API_KEY", ""),
		AITimeout:       time.Duration(envInt("AI_TIMEOUT_SECONDS", 15)) * time.Second,
		AIMaxRetries:    envInt("AI_MAX_RETRIES", 2),
		AlertWebhookURL: envString("ALERT_WEBHOOK_URL", ""),
		APIKeyOperator:  envString("API_KEY_OPERATOR", "dev-operator-key"),
		APIKeyApprover:  envString("API_KEY_APPROVER", "dev-approver-key"),
		APIKeyAdmin:     envString("API_KEY_ADMIN", "dev-admin-key"),
		APIKeyAIService: envString("API_KEY_AI_SERVICE", "dev-ai-service-key"),
		RateLimitRPS:    envFloat("RATE_LIMIT_RPS", 50),
		RateLimitBurst:  envInt("RATE_LIMIT_BURST", 100),
		LogLevel:        strings.ToLower(envString("LOG_LEVEL", "info")),
	}

	originsRaw := envString("CORS_ALLOWED_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000")
	for _, o := range strings.Split(originsRaw, ",") {
		if trimmed := strings.TrimSpace(o); trimmed != "" {
			cfg.CORSAllowedOrigins = append(cfg.CORSAllowedOrigins, trimmed)
		}
	}

	// Apply provider defaults if omitted.
	switch cfg.AIProvider {
	case "ollama":
		if cfg.AIBaseURL == "" {
			cfg.AIBaseURL = "http://127.0.0.1:11434"
		}
		if cfg.AIModel == "" {
			cfg.AIModel = "llama3.2:1b"
		}
	case "openai":
		if cfg.AIBaseURL == "" {
			cfg.AIBaseURL = "https://api.openai.com/v1"
		}
		if cfg.AIModel == "" {
			cfg.AIModel = "gpt-4o-mini"
		}
	}

	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func (c Config) Validate() error {
	if c.Port < 0 || c.Port > 65535 {
		return fmt.Errorf("invalid PORT %d: must be 0..65535", c.Port)
	}
	if strings.TrimSpace(c.DatabaseURL) == "" {
		return fmt.Errorf("DATABASE_URL must not be empty")
	}
	if c.EventBroker != "memory" && c.EventBroker != "redis" {
		return fmt.Errorf("invalid EVENT_BROKER %q: must be 'memory' or 'redis'", c.EventBroker)
	}
	if c.EventBroker == "redis" && strings.TrimSpace(c.RedisAddr) == "" {
		return fmt.Errorf("REDIS_ADDR is required when EVENT_BROKER=redis")
	}
	if c.QueueCapacity < 1 || c.QueueCapacity > 100000 {
		return fmt.Errorf("invalid QUEUE_CAPACITY %d: must be 1..100000", c.QueueCapacity)
	}
	if c.WorkerCount < 1 || c.WorkerCount > 128 {
		return fmt.Errorf("invalid WORKER_COUNT %d: must be 1..128", c.WorkerCount)
	}
	if c.MaxRetries < 0 || c.MaxRetries > 10 {
		return fmt.Errorf("invalid MAX_RETRIES %d: must be 0..10", c.MaxRetries)
	}
	if c.AIProvider != "none" && c.AIProvider != "ollama" && c.AIProvider != "openai" {
		return fmt.Errorf("invalid AI_PROVIDER %q: must be 'none', 'ollama', or 'openai'", c.AIProvider)
	}
	if c.AIProvider == "openai" && strings.TrimSpace(c.AIAPIKey) == "" {
		return fmt.Errorf("AI_API_KEY is required when AI_PROVIDER=openai")
	}
	if c.AITimeout <= 0 {
		return fmt.Errorf("AI_TIMEOUT_SECONDS must be positive")
	}
	if c.RateLimitRPS <= 0 || c.RateLimitBurst < 1 {
		return fmt.Errorf("RATE_LIMIT_RPS and RATE_LIMIT_BURST must be positive")
	}
	if strings.TrimSpace(c.APIKeyOperator) == "" || strings.TrimSpace(c.APIKeyApprover) == "" || strings.TrimSpace(c.APIKeyAdmin) == "" {
		return fmt.Errorf("API_KEY_OPERATOR, API_KEY_APPROVER, and API_KEY_ADMIN must be non-empty")
	}
	return nil
}

func envString(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return strings.TrimSpace(v)
	}
	return fallback
}

func envInt(key string, fallback int) int {
	raw, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(raw) == "" {
		return fallback
	}
	v, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil {
		return fallback
	}
	return v
}

func envFloat(key string, fallback float64) float64 {
	raw, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(raw) == "" {
		return fallback
	}
	v, err := strconv.ParseFloat(strings.TrimSpace(raw), 64)
	if err != nil {
		return fallback
	}
	return v
}
