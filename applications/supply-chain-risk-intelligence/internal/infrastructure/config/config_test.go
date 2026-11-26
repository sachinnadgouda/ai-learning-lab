package config

import (
	"testing"
)

func TestLoadFromEnv_DefaultsValid(t *testing.T) {
	t.Setenv("AI_PROVIDER", "none")
	t.Setenv("EVENT_BROKER", "memory")
	cfg, err := LoadFromEnv()
	if err != nil {
		t.Fatalf("expected default config to validate, got %v", err)
	}
	if cfg.Port != 8080 || cfg.WorkerCount != 4 || cfg.QueueCapacity != 256 {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestConfigValidate_RejectsInvalidValues(t *testing.T) {
	base, err := LoadFromEnv()
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}

	badBroker := base
	badBroker.EventBroker = "kafka"
	if err := badBroker.Validate(); err == nil {
		t.Fatal("expected error for invalid EVENT_BROKER")
	}

	badOpenAI := base
	badOpenAI.AIProvider = "openai"
	badOpenAI.AIAPIKey = ""
	if err := badOpenAI.Validate(); err == nil {
		t.Fatal("expected error when AI_PROVIDER=openai without AI_API_KEY")
	}

	badWorkers := base
	badWorkers.WorkerCount = 0
	if err := badWorkers.Validate(); err == nil {
		t.Fatal("expected error for WORKER_COUNT=0")
	}
}
