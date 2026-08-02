package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/delivery"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/httpapi"
	"glucose-monitor/backend/internal/store"
	"glucose-monitor/backend/internal/telegram"
)

const (
	stalenessMaxConcurrency = 4
	stalenessListTimeout    = 5 * time.Second
	stalenessPatientTimeout = 5 * time.Second
	deliveryCycleTimeout    = 90 * time.Second
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
		values = postgres
		closeStore = postgres.Close
	} else {
		values = store.NewMemory()
		closeStore = func() {}
	}
	defer closeStore()
	if config.hasBootstrapAccess() {
		bootstrapper, ok := values.(interface {
			BootstrapAccess(context.Context, store.BootstrapIdentity) error
		})
		if !ok {
			logger.Error("access bootstrap is not supported by the configured store")
			os.Exit(1)
		}
		if err := bootstrapper.BootstrapAccess(context.Background(), config.bootstrapIdentity()); err != nil {
			logger.Error("bootstrap development access", "error", err)
			os.Exit(1)
		}
	}
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	api := httpapi.New(httpapi.Config{
		Logger: logger, FamilyWebOrigins: config.familyWebOrigins,
		DeviceAPIOrigin: config.deviceAPIOrigin,
	}, values, engine)
	startupAt := time.Now().UTC()
	if config.production {
		if err := values.ValidateProductionAccess(context.Background(), startupAt); err != nil {
			logger.Error("production access is not fully provisioned", "error", err)
			os.Exit(1)
		}
		if err := validateProductionNotifications(context.Background(), config, values); err != nil {
			logger.Error("production notifications are not fully configured", "error", err)
			os.Exit(1)
		}
	}
	deliveryWorker := delivery.NewWorker(values, telegram.Client{Token: config.telegramToken}, logger)

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
			func(ctx context.Context, at time.Time) {
				patientIDs, err := loadPatientIDsForStaleness(ctx, stalenessListTimeout, values.PatientIDs)
				if err != nil {
					logger.Error("list patients for signal freshness", "error", err)
					return
				}
				checkPatientsStaleness(
					ctx, patientIDs, at,
					stalenessMaxConcurrency, stalenessPatientTimeout,
					api.CheckStaleness,
				)
			},
			func(ctx context.Context, _ time.Time) {
				runBoundedDeliveryCycle(ctx, deliveryCycleTimeout, time.Now().UTC(), deliveryWorker.RunOnce)
			},
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

func loadPatientIDsForStaleness(
	ctx context.Context,
	timeout time.Duration,
	load func(context.Context) ([]string, error),
) ([]string, error) {
	boundedCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return load(boundedCtx)
}

func runBoundedDeliveryCycle(
	ctx context.Context,
	timeout time.Duration,
	at time.Time,
	run func(context.Context, time.Time),
) {
	boundedCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	run(boundedCtx, at)
}

type telegramRecipientReadiness interface {
	HasTelegramRecipients(context.Context) (bool, error)
}

func validateProductionNotifications(ctx context.Context, config appConfig, readiness telegramRecipientReadiness) error {
	if !config.production {
		return nil
	}
	hasRecipients, err := readiness.HasTelegramRecipients(ctx)
	if err != nil {
		return fmt.Errorf("check database Telegram recipients: %w", err)
	}
	if hasRecipients && strings.TrimSpace(config.telegramToken) == "" {
		return errors.New("TELEGRAM_BOT_TOKEN is required because database Telegram recipients are configured")
	}
	return nil
}

func checkPatientsStaleness(
	ctx context.Context,
	patientIDs []string,
	at time.Time,
	maxConcurrency int,
	perPatientTimeout time.Duration,
	check func(context.Context, string, time.Time),
) {
	if len(patientIDs) == 0 || maxConcurrency <= 0 {
		return
	}
	if maxConcurrency > len(patientIDs) {
		maxConcurrency = len(patientIDs)
	}
	jobs := make(chan string)
	var workers sync.WaitGroup
	workers.Add(maxConcurrency)
	for worker := 0; worker < maxConcurrency; worker++ {
		go func() {
			defer workers.Done()
			for patientID := range jobs {
				patientCtx, cancel := context.WithTimeout(ctx, perPatientTimeout)
				check(patientCtx, patientID, at)
				cancel()
			}
		}()
	}
sendPatients:
	for _, patientID := range patientIDs {
		select {
		case jobs <- patientID:
		case <-ctx.Done():
			break sendPatients
		}
	}
	close(jobs)
	workers.Wait()
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
	environment                 string
	address                     string
	production                  bool
	bootstrapDeviceToken        string
	bootstrapFamilyToken        string
	bootstrapPatientID          string
	bootstrapHouseholdID        string
	bootstrapDeviceID           string
	bootstrapBackendBindingID   string
	bootstrapCredentialID       string
	bootstrapCredentialRevision int64
	bootstrapFamilySessionID    string
	telegramToken               string
	telegramChatIDs             []string
	databaseURL                 string
	familyWebOrigins            []string
	deviceAPIOrigin             string
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
	if config.production && config.databaseURL == "" {
		return errors.New("DATABASE_URL is required in production")
	}
	if config.production && strings.TrimSpace(config.telegramToken) == "" {
		return errors.New("TELEGRAM_BOT_TOKEN is required in production")
	}
	if config.production && config.hasBootstrapAccess() {
		return errors.New("plaintext startup access provisioning is forbidden in production")
	}
	if !config.production && config.hasBootstrapAccess() {
		if config.bootstrapDeviceToken == "" || config.bootstrapFamilyToken == "" || config.bootstrapPatientID == "" {
			return errors.New("DEVICE_TOKEN, FAMILY_SESSION_TOKEN and PATIENT_ID must be set together for development bootstrap")
		}
		if err := (store.DeviceBinding{
			DeviceID:           config.bootstrapDeviceID,
			BackendBindingID:   config.bootstrapBackendBindingID,
			CredentialID:       config.bootstrapCredentialID,
			CredentialRevision: config.bootstrapCredentialRevision,
		}).Validate(); err != nil {
			return errors.New("development device credential binding is incomplete or malformed")
		}
		if config.bootstrapDeviceToken == config.bootstrapFamilyToken {
			return errors.New("DEVICE_TOKEN and FAMILY_SESSION_TOKEN must be different")
		}
		if len(config.bootstrapDeviceToken) < 32 || len(config.bootstrapFamilyToken) < 32 {
			return errors.New("development bootstrap tokens must each contain at least 32 characters")
		}
		for name, value := range map[string]string{
			"PATIENT_ID":                  config.bootstrapPatientID,
			"BOOTSTRAP_HOUSEHOLD_ID":      config.bootstrapHouseholdID,
			"BOOTSTRAP_DEVICE_ID":         config.bootstrapDeviceID,
			"BOOTSTRAP_FAMILY_SESSION_ID": config.bootstrapFamilySessionID,
		} {
			if !domain.IsUUID(value) {
				return errors.New(name + " must be a UUID")
			}
		}
	}
	if !config.production && config.databaseURL == "" && !config.hasBootstrapAccess() {
		return errors.New("development memory store requires explicit access bootstrap credentials")
	}
	if len(config.telegramChatIDs) > 0 && config.telegramToken == "" {
		return errors.New("TELEGRAM_BOT_TOKEN is required when TELEGRAM_CHAT_IDS is configured")
	}
	if len(config.telegramChatIDs) > 0 && !config.hasBootstrapAccess() {
		return errors.New("TELEGRAM_CHAT_IDS is allowed only for an explicit development household bootstrap")
	}
	if err := validateFamilyWebOrigins(config); err != nil {
		return err
	}
	if err := validateDeviceAPIOrigin(config); err != nil {
		return err
	}
	return nil
}

func validateFamilyWebOrigins(config appConfig) error {
	if len(config.familyWebOrigins) == 0 {
		return errors.New("FAMILY_WEB_ORIGINS must contain at least one exact web origin")
	}
	seen := make(map[string]struct{}, len(config.familyWebOrigins))
	for _, origin := range config.familyWebOrigins {
		if err := validateExactOrigin(origin, config.production, "FAMILY_WEB_ORIGINS"); err != nil {
			return err
		}
		if _, duplicate := seen[origin]; duplicate {
			return fmt.Errorf("FAMILY_WEB_ORIGINS contains duplicate origin %q", origin)
		}
		seen[origin] = struct{}{}
	}
	return nil
}

func validateDeviceAPIOrigin(config appConfig) error {
	return validateExactOrigin(config.deviceAPIOrigin, config.production, "DEVICE_API_ORIGIN")
}

func validateExactOrigin(origin string, production bool, name string) error {
	if origin == "" || strings.TrimSpace(origin) != origin {
		return fmt.Errorf("%s must contain one exact origin", name)
	}
	parsed, err := url.Parse(origin)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" || parsed.User != nil ||
		parsed.Path != "" || parsed.RawPath != "" || parsed.RawQuery != "" || parsed.Fragment != "" ||
		parsed.ForceQuery || parsed.String() != origin {
		return fmt.Errorf("%s contains an invalid origin %q", name, origin)
	}
	secure := parsed.Scheme == "https"
	loopbackDevelopment := !production && parsed.Scheme == "http" && isLoopbackHost(parsed.Hostname())
	if !secure && !loopbackDevelopment {
		return fmt.Errorf("%s contains an insecure origin %q", name, origin)
	}
	return nil
}

func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	address := net.ParseIP(host)
	return address != nil && address.IsLoopback()
}

func loadConfig() appConfig {
	environment := strings.TrimSpace(os.Getenv("APP_ENV"))
	return appConfig{
		environment: environment,
		address:     env("HTTP_ADDRESS", ":8080"), production: environment == "production",
		bootstrapDeviceToken:        strings.TrimSpace(os.Getenv("DEVICE_TOKEN")),
		bootstrapFamilyToken:        strings.TrimSpace(os.Getenv("FAMILY_SESSION_TOKEN")),
		bootstrapPatientID:          strings.TrimSpace(os.Getenv("PATIENT_ID")),
		bootstrapHouseholdID:        env("BOOTSTRAP_HOUSEHOLD_ID", developmentOnly(environment, "00000000-0000-4000-8000-000000000100")),
		bootstrapDeviceID:           env("BOOTSTRAP_DEVICE_ID", developmentOnly(environment, "00000000-0000-4000-8000-000000000200")),
		bootstrapBackendBindingID:   strings.TrimSpace(os.Getenv("BACKEND_BINDING_ID")),
		bootstrapCredentialID:       strings.TrimSpace(os.Getenv("CREDENTIAL_ID")),
		bootstrapCredentialRevision: envInt64("CREDENTIAL_REVISION"),
		bootstrapFamilySessionID:    env("BOOTSTRAP_FAMILY_SESSION_ID", developmentOnly(environment, "00000000-0000-4000-8000-000000000300")),
		telegramToken:               strings.TrimSpace(os.Getenv("TELEGRAM_BOT_TOKEN")),
		telegramChatIDs:             splitCSV(os.Getenv("TELEGRAM_CHAT_IDS")),
		databaseURL:                 strings.TrimSpace(os.Getenv("DATABASE_URL")),
		familyWebOrigins:            splitCSV(os.Getenv("FAMILY_WEB_ORIGINS")),
		deviceAPIOrigin:             strings.TrimSpace(os.Getenv("DEVICE_API_ORIGIN")),
	}
}

func (c appConfig) hasBootstrapAccess() bool {
	return c.bootstrapDeviceToken != "" || c.bootstrapFamilyToken != "" || c.bootstrapPatientID != "" ||
		c.bootstrapBackendBindingID != "" || c.bootstrapCredentialID != "" || c.bootstrapCredentialRevision != 0
}

func (c appConfig) bootstrapIdentity() store.BootstrapIdentity {
	return store.BootstrapIdentity{
		HouseholdID: canonicalUUID(c.bootstrapHouseholdID), PatientID: canonicalUUID(c.bootstrapPatientID),
		DeviceID: canonicalUUID(c.bootstrapDeviceID), DeviceTokenHash: store.HashAccessToken(c.bootstrapDeviceToken),
		BackendBindingID: c.bootstrapBackendBindingID, CredentialID: c.bootstrapCredentialID,
		CredentialRevision: c.bootstrapCredentialRevision,
		FamilySessionID:    canonicalUUID(c.bootstrapFamilySessionID), FamilyTokenHash: store.HashAccessToken(c.bootstrapFamilyToken),
		TelegramRecipients: c.telegramChatIDs,
	}
}

func canonicalUUID(value string) string {
	if domain.IsUUID(value) {
		return strings.ToLower(value)
	}
	return value
}

func envInt64(name string) int64 {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return 0
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0
	}
	return parsed
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
