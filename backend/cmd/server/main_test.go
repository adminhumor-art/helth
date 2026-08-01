package main

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestProductionConfigRequiresDatabase(t *testing.T) {
	err := validateConfig(appConfig{
		environment: "production",
		production:  true,
		deviceToken: "0123456789abcdef0123456789abcdef",
		familyToken: "fedcba9876543210fedcba9876543210",
		patientID:   "00000000-0000-4000-8000-000000000001",
	})
	if err == nil {
		t.Fatal("production accepted ephemeral memory store")
	}
}

func TestProductionConfigKeepsDeviceAndFamilyAuthorizationSeparate(t *testing.T) {
	shared := "0123456789abcdef0123456789abcdef"
	err := validateConfig(appConfig{
		environment: "production", production: true, deviceToken: shared, familyToken: shared,
		patientID:   "00000000-0000-4000-8000-000000000001",
		databaseURL: "postgres://database.invalid/sladkaya",
	})
	if err == nil {
		t.Fatal("production accepted one token for both device ingest and family access")
	}
}

func TestProductionConfigRejectsWeakAuthorizationTokens(t *testing.T) {
	err := validateConfig(appConfig{
		environment: "production", production: true, deviceToken: "short-device", familyToken: "short-family",
		patientID:   "00000000-0000-4000-8000-000000000001",
		databaseURL: "postgres://database.invalid/sladkaya",
	})
	if err == nil {
		t.Fatal("production accepted guessable authorization tokens")
	}
}

func TestConfigRejectsUnknownEnvironmentInsteadOfFallingBackToDevelopment(t *testing.T) {
	err := validateConfig(appConfig{environment: "prod"})
	if err == nil {
		t.Fatal("unknown APP_ENV silently enabled development credentials")
	}
}

func TestConfigRejectsMissingEnvironmentInsteadOfFallingBackToDevelopment(t *testing.T) {
	t.Setenv("APP_ENV", "")
	config := loadConfig()
	if err := validateConfig(config); err == nil {
		t.Fatal("missing APP_ENV silently enabled development credentials and ephemeral storage")
	}
}

func TestDevelopmentConfigKeepsDeviceAndFamilyAuthorizationSeparate(t *testing.T) {
	shared := "shared-local-token"
	err := validateConfig(appConfig{
		environment: "development",
		deviceToken: shared,
		familyToken: shared,
		patientID:   "00000000-0000-4000-8000-000000000001",
	})
	if err == nil {
		t.Fatal("development accepted one token for both device ingest and family access")
	}
}

func TestDeliveryWorkCannotBlockStalenessScheduler(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	deliveryStarted := make(chan struct{})
	releaseDelivery := make(chan struct{})
	var staleCalls atomic.Int32
	done := make(chan struct{})
	go func() {
		runSchedulers(
			ctx,
			5*time.Millisecond,
			5*time.Millisecond,
			func(context.Context, time.Time) { staleCalls.Add(1) },
			func(context.Context, time.Time) {
				select {
				case <-deliveryStarted:
				default:
					close(deliveryStarted)
				}
				select {
				case <-releaseDelivery:
				case <-ctx.Done():
				}
			},
		)
		close(done)
	}()
	select {
	case <-deliveryStarted:
	case <-time.After(time.Second):
		t.Fatal("delivery scheduler did not start")
	}
	deadline := time.Now().Add(time.Second)
	for staleCalls.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if staleCalls.Load() == 0 {
		t.Fatal("blocked delivery also blocked staleness")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("schedulers did not stop after context cancellation")
	}
}
