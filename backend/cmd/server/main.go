package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/delivery"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/httpapi"
	"glucose-monitor/backend/internal/store"
	"glucose-monitor/backend/internal/telegram"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	config := loadConfig()
	if err := validateConfig(config); err != nil {
		logger.Error("invalid configuration", "error", err)
		os.Exit(1)
	}
	var values store.Store
	var closeStore func()
	if config.databaseURL != "" {
		postgres, err := store.NewPostgres(context.Background(), config.databaseURL)
		if err != nil {
			logger.Error("connect to postgres", "error", err)
			os.Exit(1)
		}
		if err := postgres.InitializeSchema(context.Background()); err != nil {
			logger.Error("initialize postgres schema", "error", err)
			os.Exit(1)
		}
		if err := postgres.Bootstrap(context.Background(), config.patientID); err != nil {
			logger.Error("bootstrap postgres", "error", err)
			os.Exit(1)
		}
		values = postgres
		closeStore = postgres.Close
	} else {
		values = store.NewMemory()
		closeStore = func() {}
	}
	defer closeStore()
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	api := httpapi.New(httpapi.Config{
		DeviceToken: config.deviceToken, FamilySessionToken: config.familyToken,
		PatientID: config.patientID, Logger: logger, TelegramRecipients: config.telegramChatIDs,
	}, values, engine)
	deliveryWorker := delivery.NewWorker(values, telegram.Client{Token: config.telegramToken}, logger)
	startupAt := time.Now().UTC()
	if err := api.PrimePatient(context.Background(), config.patientID, startupAt); err != nil {
		logger.Error("prime patient alert state", "error", err)
		os.Exit(1)
	}

	server := &http.Server{
		Addr: config.address, Handler: api.Handler(),
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(stop)
	schedulerCtx, stopSchedulers := context.WithCancel(context.Background())
	schedulersDone := make(chan struct{})
	go func() {
		runSchedulers(
			schedulerCtx,
			30*time.Second,
			2*time.Second,
			func(ctx context.Context, at time.Time) { api.CheckStaleness(ctx, config.patientID, at) },
			func(ctx context.Context, _ time.Time) { deliveryWorker.RunOnce(ctx, time.Now().UTC()) },
		)
		close(schedulersDone)
	}()
	go func() {
		logger.Info("api listening", "address", config.address)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("api stopped", "error", err)
			os.Exit(1)
		}
	}()

	<-stop
	stopSchedulers()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
	select {
	case <-schedulersDone:
	case <-ctx.Done():
		logger.Error("background schedulers did not stop", "error", ctx.Err())
	}
}

func runSchedulers(
	ctx context.Context,
	staleInterval time.Duration,
	deliveryInterval time.Duration,
	checkStaleness func(context.Context, time.Time),
	runDelivery func(context.Context, time.Time),
) {
	staleCtx, stopStaleness := context.WithCancel(ctx)
	defer stopStaleness()
	deliveryCtx, stopDelivery := context.WithCancel(ctx)
	defer stopDelivery()
	done := make(chan struct{}, 2)
	go func() {
		runScheduler(staleCtx, staleInterval, checkStaleness)
		done <- struct{}{}
	}()
	go func() {
		runScheduler(deliveryCtx, deliveryInterval, runDelivery)
		done <- struct{}{}
	}()
	<-done
	<-done
}

func runScheduler(ctx context.Context, interval time.Duration, task func(context.Context, time.Time)) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case at := <-ticker.C:
			task(ctx, at.UTC())
		case <-ctx.Done():
			return
		}
	}
}

type appConfig struct {
	environment     string
	address         string
	production      bool
	deviceToken     string
	familyToken     string
	patientID       string
	telegramToken   string
	telegramChatIDs []string
	databaseURL     string
}

func validateConfig(config appConfig) error {
	switch config.environment {
	case "development", "test", "production":
	default:
		return errors.New("APP_ENV must be explicitly set to development, test or production")
	}
	if (config.environment == "production") != config.production {
		return errors.New("APP_ENV and production mode are inconsistent")
	}
	if config.deviceToken != "" && config.deviceToken == config.familyToken {
		return errors.New("DEVICE_TOKEN and FAMILY_SESSION_TOKEN must be different")
	}
	if !config.production {
		return nil
	}
	if config.deviceToken == "" || config.familyToken == "" || config.patientID == "" {
		return errors.New("DEVICE_TOKEN, FAMILY_SESSION_TOKEN and PATIENT_ID are required in production")
	}
	if config.databaseURL == "" {
		return errors.New("DATABASE_URL is required in production")
	}
	if !domain.IsUUID(config.patientID) {
		return errors.New("PATIENT_ID must be a UUID in production")
	}
	if len(config.deviceToken) < 32 || len(config.familyToken) < 32 {
		return errors.New("DEVICE_TOKEN and FAMILY_SESSION_TOKEN must each contain at least 32 characters in production")
	}
	if len(config.telegramChatIDs) > 0 && config.telegramToken == "" {
		return errors.New("TELEGRAM_BOT_TOKEN is required when TELEGRAM_CHAT_IDS is configured")
	}
	return nil
}

func loadConfig() appConfig {
	environment := strings.TrimSpace(os.Getenv("APP_ENV"))
	return appConfig{
		environment: environment,
		address:     env("HTTP_ADDRESS", ":8080"), production: environment == "production",
		deviceToken:     env("DEVICE_TOKEN", developmentOnly(environment, "dev-device-token")),
		familyToken:     env("FAMILY_SESSION_TOKEN", developmentOnly(environment, "dev-family-token")),
		patientID:       env("PATIENT_ID", developmentOnly(environment, "00000000-0000-4000-8000-000000000001")),
		telegramToken:   os.Getenv("TELEGRAM_BOT_TOKEN"),
		telegramChatIDs: splitCSV(os.Getenv("TELEGRAM_CHAT_IDS")),
		databaseURL:     strings.TrimSpace(os.Getenv("DATABASE_URL")),
	}
}

func developmentOnly(environment, value string) string {
	if environment == "production" {
		return ""
	}
	return value
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func splitCSV(value string) []string {
	var result []string
	for _, item := range strings.Split(value, ",") {
		if item = strings.TrimSpace(item); item != "" {
			result = append(result, item)
		}
	}
	return result
}
