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
	"glucose-monitor/backend/internal/httpapi"
	"glucose-monitor/backend/internal/store"
	"glucose-monitor/backend/internal/telegram"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	config := loadConfig()
	if config.production && (config.deviceToken == "" || config.familyToken == "" || config.patientID == "") {
		logger.Error("DEVICE_TOKEN, FAMILY_SESSION_TOKEN and PATIENT_ID are required in production")
		os.Exit(1)
	}
	if config.production && len(config.telegramChatIDs) > 0 && config.telegramToken == "" {
		logger.Error("TELEGRAM_BOT_TOKEN is required when TELEGRAM_CHAT_IDS is configured")
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
		if err := postgres.Migrate(context.Background()); err != nil {
			logger.Error("migrate postgres", "error", err)
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
	staleTicker := time.NewTicker(30 * time.Second)
	defer staleTicker.Stop()
	deliveryTicker := time.NewTicker(2 * time.Second)
	defer deliveryTicker.Stop()
	staleDone := make(chan struct{})
	go func() {
		for {
			select {
			case at := <-staleTicker.C:
				api.CheckStaleness(context.Background(), config.patientID, at.UTC())
			case at := <-deliveryTicker.C:
				deliveryWorker.RunOnce(context.Background(), at.UTC())
			case <-staleDone:
				return
			}
		}
	}()
	go func() {
		logger.Info("api listening", "address", config.address)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("api stopped", "error", err)
			os.Exit(1)
		}
	}()

	<-stop
	close(staleDone)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
}

type appConfig struct {
	address         string
	production      bool
	deviceToken     string
	familyToken     string
	patientID       string
	telegramToken   string
	telegramChatIDs []string
	databaseURL     string
}

func loadConfig() appConfig {
	environment := env("APP_ENV", "development")
	return appConfig{
		address: env("HTTP_ADDRESS", ":8080"), production: environment == "production",
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
