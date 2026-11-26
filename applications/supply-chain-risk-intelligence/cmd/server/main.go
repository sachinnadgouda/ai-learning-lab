package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/application"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/domain"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/ai"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/broker"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/config"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/httpapi"
	"github.com/ai-learning-lab/supply-chain-risk-intelligence/internal/infrastructure/sqlite"
)

func main() {
	seedFlag := flag.Bool("seed", false, "Seed demonstration supply chain entities into SQLite on startup")
	flag.Parse()

	cfg, err := config.LoadFromEnv()
	if err != nil {
		fmt.Fprintf(os.Stderr, "configuration error: %v\n", err)
		os.Exit(1)
	}

	logger := newLogger(cfg.LogLevel)
	slog.SetDefault(logger)

	store, err := sqlite.Open(cfg.DatabaseURL)
	if err != nil {
		logger.Error("sqlite_open_failed", "error", err)
		os.Exit(1)
	}
	defer func() { _ = store.Close() }()

	if *seedFlag {
		if err := application.SeedRepository(context.Background(), store, time.Now().UTC()); err != nil {
			logger.Error("seed_failed", "error", err)
			os.Exit(1)
		}
		logger.Info("seed_dataset_loaded")
	}

	var eventBroker application.EventBroker
	if cfg.EventBroker == "redis" {
		eventBroker = broker.NewRedisBroker(cfg.RedisAddr, cfg.RedisStream, cfg.RedisGroup, cfg.QueueCapacity)
	} else {
		eventBroker = broker.NewMemoryBroker(cfg.QueueCapacity)
	}

	var investigator application.AIInvestigator
	if cfg.AIProvider == "ollama" || cfg.AIProvider == "openai" {
		investigator = ai.NewHTTPInvestigator(ai.HTTPInvestigatorConfig{
			Provider:   cfg.AIProvider,
			BaseURL:    cfg.AIBaseURL,
			Model:      cfg.AIModel,
			APIKey:     cfg.AIAPIKey,
			Timeout:    cfg.AITimeout,
			MaxRetries: cfg.AIMaxRetries,
		})
	} else {
		investigator = ai.NewNoneInvestigator()
	}

	var notifier application.Notifier = application.NewLogNotifier(logger)
	if cfg.AlertWebhookURL != "" {
		notifier = application.NewWebhookNotifier(cfg.AlertWebhookURL, 3*time.Second, 2)
	}

	metrics := application.NewMetrics()
	engine := domain.NewRiskEngine()

	processor := application.NewProcessor(
		application.ProcessorConfig{
			WorkerCount:    cfg.WorkerCount,
			MaxRetries:     cfg.MaxRetries,
			BaseBackoff:    cfg.RetryBase,
			MaxBackoff:     cfg.RetryMax,
			ProcessTimeout: 5 * time.Second,
		},
		eventBroker,
		store,
		engine,
		notifier,
		metrics,
		logger,
	)

	workerCtx, cancelWorkers := context.WithCancel(context.Background())
	defer cancelWorkers()
	processor.Start(workerCtx)

	invService := application.NewInvestigationService(store, investigator, metrics, logger)
	govService := application.NewGovernanceService(store, processor)

	auth := httpapi.NewKeyAuthenticator(cfg.APIKeyOperator, cfg.APIKeyApprover, cfg.APIKeyAdmin).
		WithAIServiceKey(cfg.APIKeyAIService).
		WithAuditSink(store)
	limiter := httpapi.NewIPRateLimiter(cfg.RateLimitRPS, cfg.RateLimitBurst)

	handler := httpapi.NewHandler(httpapi.ServerDeps{
		Repo:          store,
		Broker:        eventBroker,
		Processor:     processor,
		Investigator:  invService,
		Governance:    govService,
		Engine:        engine,
		Metrics:       metrics,
		Auth:          auth,
		RateLimiter:   limiter,
		AllowedOrigin: cfg.CORSAllowedOrigins,
		Logger:        logger,
	})

	addr := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("server_starting",
			"addr", addr,
			"broker", eventBroker.Name(),
			"ai_provider", investigator.ProviderName(),
			"workers", cfg.WorkerCount,
			"queue_capacity", cfg.QueueCapacity,
		)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		logger.Info("shutdown_signal_received", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("http_server_error", "error", err)
			os.Exit(1)
		}
	}

	// Graceful shutdown: drain HTTP first, then drain buffered worker pool events.
	httpCtx, cancelHTTP := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelHTTP()
	if err := httpSrv.Shutdown(httpCtx); err != nil {
		logger.Error("http_shutdown_error", "error", err)
	}

	drainCtx, cancelDrain := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancelDrain()
	if err := processor.Shutdown(drainCtx); err != nil {
		logger.Error("processor_drain_error", "error", err)
	}
	cancelWorkers()
	logger.Info("server_stopped_cleanly")
}

func newLogger(levelStr string) *slog.Logger {
	level := slog.LevelInfo
	switch levelStr {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
}
