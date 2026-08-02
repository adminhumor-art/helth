package main

import (
	"bufio"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"glucose-monitor/backend/internal/store"
)

func TestProductionConfigRequiresDatabase(t *testing.T) {
	err := validateConfig(appConfig{
		environment: "production",
		production:  true,
	})
	if err == nil {
		t.Fatal("production accepted ephemeral memory store")
	}
}

func TestProductionConfigRejectsPlaintextBootstrapCredentials(t *testing.T) {
	err := validateConfig(appConfig{
		environment: "production", production: true,
		bootstrapDeviceToken: "0123456789abcdef0123456789abcdef",
		bootstrapFamilyToken: "fedcba9876543210fedcba9876543210",
		bootstrapPatientID:   "00000000-0000-4000-8000-000000000001",
		databaseURL:          "postgres://database.invalid/sladkaya",
	})
	if err == nil {
		t.Fatal("production accepted plaintext startup provisioning credentials")
	}
}

func TestProductionConfigUsesPreprovisionedDatabaseWithoutGlobalPatient(t *testing.T) {
	err := validateConfig(appConfig{
		environment: "production", production: true,
		databaseURL:   "postgres://database.invalid/sladkaya",
		telegramToken: "production-bot-token",
	})
	if err != nil {
		t.Fatalf("preprovisioned production database was rejected: %v", err)
	}
}

func TestProductionConfigRequiresTelegramBotTokenBeforeRecipientsAreProvisioned(t *testing.T) {
	for _, token := range []string{"", " \t\n"} {
		err := validateConfig(appConfig{
			environment:   "production",
			production:    true,
			databaseURL:   "postgres://database.invalid/sladkaya",
			telegramToken: token,
		})
		if err == nil || !strings.Contains(err.Error(), "TELEGRAM_BOT_TOKEN") {
			t.Fatalf("production accepted missing Telegram bot token %q: %v", token, err)
		}
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
		environment:          "development",
		bootstrapDeviceToken: shared,
		bootstrapFamilyToken: shared,
		bootstrapPatientID:   "00000000-0000-4000-8000-000000000001",
	})
	if err == nil {
		t.Fatal("development accepted one token for both device ingest and family access")
	}
}

func TestDevelopmentBootstrapMustBeCompleteAndStrong(t *testing.T) {
	for _, config := range []appConfig{
		{environment: "development", bootstrapDeviceToken: "only-one-value"},
		{
			environment: "development", bootstrapDeviceToken: "short-device",
			bootstrapFamilyToken: "short-family", bootstrapPatientID: "00000000-0000-4000-8000-000000000001",
		},
	} {
		if err := validateConfig(config); err == nil {
			t.Fatalf("unsafe development bootstrap was accepted: %#v", config)
		}
	}
}

func TestDevelopmentConfigAcceptsCompleteExplicitBootstrap(t *testing.T) {
	err := validateConfig(appConfig{
		environment:                 "development",
		bootstrapDeviceToken:        "device-token-0123456789abcdef0123456789abcdef",
		bootstrapFamilyToken:        "family-token-fedcba9876543210fedcba9876543210",
		bootstrapPatientID:          "00000000-0000-4000-8000-000000000001",
		bootstrapHouseholdID:        "00000000-0000-4000-8000-000000000100",
		bootstrapDeviceID:           "00000000-0000-4000-8000-000000000200",
		bootstrapBackendBindingID:   "binding-cn-gs1-001",
		bootstrapCredentialID:       "credential-cn-gs1-001",
		bootstrapCredentialRevision: 1,
		bootstrapFamilySessionID:    "00000000-0000-4000-8000-000000000300",
	})
	if err != nil {
		t.Fatalf("complete development bootstrap was rejected: %v", err)
	}
}

func TestComposeDevelopmentDefaultsPassServerValidation(t *testing.T) {
	values := composeAPIEnvironment(t, filepath.Join("..", "..", "..", "compose.yaml"))
	for _, required := range []string{
		"BOOTSTRAP_DEVICE_ID",
		"BACKEND_BINDING_ID",
		"CREDENTIAL_ID",
		"CREDENTIAL_REVISION",
	} {
		if strings.TrimSpace(values[required]) == "" {
			t.Fatalf("compose api environment does not provide %s", required)
		}
	}

	configEnvironmentNames := []string{
		"APP_ENV", "HTTP_ADDRESS", "DEVICE_TOKEN", "FAMILY_SESSION_TOKEN", "PATIENT_ID",
		"BOOTSTRAP_HOUSEHOLD_ID", "BOOTSTRAP_DEVICE_ID", "BACKEND_BINDING_ID", "CREDENTIAL_ID",
		"CREDENTIAL_REVISION", "BOOTSTRAP_FAMILY_SESSION_ID", "DATABASE_URL", "TELEGRAM_BOT_TOKEN",
		"TELEGRAM_CHAT_IDS",
	}
	for _, name := range configEnvironmentNames {
		t.Setenv(name, "")
	}
	for name, value := range values {
		t.Setenv(name, expandComposeDevelopmentDefault(value))
	}
	if err := validateConfig(loadConfig()); err != nil {
		t.Fatalf("compose development defaults cannot start the backend: %v", err)
	}
}

func composeAPIEnvironment(t *testing.T, path string) map[string]string {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()

	result := make(map[string]string)
	inAPI := false
	inEnvironment := false
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		switch {
		case line == "  api:":
			inAPI = true
			inEnvironment = false
		case inAPI && strings.HasPrefix(line, "  ") && !strings.HasPrefix(line, "    "):
			inAPI = false
			inEnvironment = false
		case inAPI && line == "    environment:":
			inEnvironment = true
		case inEnvironment && strings.HasPrefix(line, "      "):
			name, value, found := strings.Cut(strings.TrimSpace(line), ":")
			if found {
				result[name] = strings.TrimSpace(value)
			}
		case inEnvironment:
			inEnvironment = false
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	return result
}

func expandComposeDevelopmentDefault(value string) string {
	for {
		start := strings.Index(value, "${")
		if start < 0 {
			return strings.Trim(value, "\"'")
		}
		endOffset := strings.Index(value[start:], "}")
		if endOffset < 0 {
			return value
		}
		end := start + endOffset
		expression := value[start+2 : end]
		_, fallback, found := strings.Cut(expression, ":-")
		if !found {
			fallback = ""
		}
		value = value[:start] + fallback + value[end+1:]
	}
}

func TestProductionConfigRejectsGlobalTelegramRecipients(t *testing.T) {
	err := validateConfig(appConfig{
		environment:     "production",
		production:      true,
		databaseURL:     "postgres://database.invalid/sladkaya",
		telegramToken:   "service-token",
		telegramChatIDs: []string{"123456789"},
	})
	if err == nil {
		t.Fatal("production accepted global Telegram recipients outside a household")
	}
}

type telegramReadinessStub struct {
	hasRecipients bool
	err           error
}

func (s telegramReadinessStub) HasTelegramRecipients(context.Context) (bool, error) {
	return s.hasRecipients, s.err
}

func TestProductionRuntimeRequiresBotTokenForDatabaseTelegramRecipients(t *testing.T) {
	for _, test := range []struct {
		name          string
		config        appConfig
		readiness     telegramReadinessStub
		shouldFail    bool
		expectedCause error
	}{
		{
			name:       "configured recipients without bot token",
			config:     appConfig{environment: "production", production: true},
			readiness:  telegramReadinessStub{hasRecipients: true},
			shouldFail: true,
		},
		{
			name:      "configured recipients with bot token",
			config:    appConfig{environment: "production", production: true, telegramToken: "bot-token"},
			readiness: telegramReadinessStub{hasRecipients: true},
		},
		{
			name:      "no configured recipients",
			config:    appConfig{environment: "production", production: true},
			readiness: telegramReadinessStub{},
		},
		{
			name:       "database check failure",
			config:     appConfig{environment: "production", production: true, telegramToken: "bot-token"},
			readiness:  telegramReadinessStub{err: context.DeadlineExceeded},
			shouldFail: true, expectedCause: context.DeadlineExceeded,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			err := validateProductionNotifications(context.Background(), test.config, test.readiness)
			if test.shouldFail && err == nil {
				t.Fatal("unsafe production notification configuration was accepted")
			}
			if !test.shouldFail && err != nil {
				t.Fatalf("safe production notification configuration was rejected: %v", err)
			}
			if test.expectedCause != nil && !errors.Is(err, test.expectedCause) {
				t.Fatalf("runtime readiness lost error cause: %v", err)
			}
		})
	}
}

func TestBootstrapIdentityCanonicalizesUUIDsFromConfig(t *testing.T) {
	config := appConfig{
		bootstrapHouseholdID:     "AAAAAAAA-0000-4000-8000-000000000100",
		bootstrapPatientID:       "BBBBBBBB-0000-4000-8000-000000000001",
		bootstrapDeviceID:        "CCCCCCCC-0000-4000-8000-000000000200",
		bootstrapFamilySessionID: "DDDDDDDD-0000-4000-8000-000000000300",
	}
	identity := config.bootstrapIdentity()
	for name, value := range map[string]string{
		"household":     identity.HouseholdID,
		"patient":       identity.PatientID,
		"device":        identity.DeviceID,
		"familySession": identity.FamilySessionID,
	} {
		if value != strings.ToLower(value) {
			t.Fatalf("%s UUID is not lowercase canonical: %q", name, value)
		}
	}
}

func TestDevelopmentConfigRejectsCredentialRevisionOutsideJSONSafeRange(t *testing.T) {
	config := appConfig{
		environment:                 "development",
		bootstrapDeviceToken:        "device-token-0123456789abcdef0123456789abcdef",
		bootstrapFamilyToken:        "family-token-fedcba9876543210fedcba9876543210",
		bootstrapPatientID:          "00000000-0000-4000-8000-000000000001",
		bootstrapHouseholdID:        "00000000-0000-4000-8000-000000000100",
		bootstrapDeviceID:           "00000000-0000-4000-8000-000000000200",
		bootstrapBackendBindingID:   "binding-cn-gs1-001",
		bootstrapCredentialID:       "credential-cn-gs1-001",
		bootstrapCredentialRevision: store.MaxCredentialRevision + 1,
		bootstrapFamilySessionID:    "00000000-0000-4000-8000-000000000300",
	}
	if err := validateConfig(config); err == nil {
		t.Fatal("development config accepted credential revision outside JSON safe-integer range")
	}
	config.bootstrapCredentialRevision = store.MaxCredentialRevision
	if err := validateConfig(config); err != nil {
		t.Fatalf("maximum JSON-safe credential revision was rejected: %v", err)
	}
}

func TestPatientStalenessChecksUsePerPatientTimeoutAndBoundedConcurrency(t *testing.T) {
	patientIDs := []string{"blocked", "fast-a", "fast-b", "fast-c", "fast-d"}
	var active atomic.Int32
	var maximum atomic.Int32
	var mu sync.Mutex
	called := make(map[string]bool)
	blockedStarted := make(chan struct{})
	startedAt := time.Now()
	checkPatientsStaleness(
		context.Background(), patientIDs, time.Now().UTC(), 2, 30*time.Millisecond,
		func(ctx context.Context, patientID string, _ time.Time) {
			current := active.Add(1)
			defer active.Add(-1)
			for {
				observed := maximum.Load()
				if current <= observed || maximum.CompareAndSwap(observed, current) {
					break
				}
			}
			if patientID == "blocked" {
				close(blockedStarted)
				<-ctx.Done()
			} else {
				select {
				case <-blockedStarted:
				case <-ctx.Done():
				}
			}
			mu.Lock()
			called[patientID] = true
			mu.Unlock()
		},
	)
	if elapsed := time.Since(startedAt); elapsed > 300*time.Millisecond {
		t.Fatalf("one blocked patient delayed the whole staleness pass: %v", elapsed)
	}
	if got := maximum.Load(); got < 2 || got > 2 {
		t.Fatalf("staleness concurrency=%d want exactly bounded parallelism 2", got)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(called) != len(patientIDs) {
		t.Fatalf("not all patients were checked: %#v", called)
	}
}

func TestPatientListAndDeliveryCyclesHaveDeadlines(t *testing.T) {
	const timeout = 20 * time.Millisecond
	patientListSawDeadline := false
	startedAt := time.Now()
	_, err := loadPatientIDsForStaleness(context.Background(), timeout, func(ctx context.Context) ([]string, error) {
		if _, ok := ctx.Deadline(); !ok {
			t.Fatal("PatientIDs context has no deadline")
		}
		patientListSawDeadline = true
		<-ctx.Done()
		return nil, ctx.Err()
	})
	if !patientListSawDeadline || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("PatientIDs deadline was not enforced: seen=%v err=%v", patientListSawDeadline, err)
	}
	if elapsed := time.Since(startedAt); elapsed > 500*time.Millisecond {
		t.Fatalf("PatientIDs timeout was not bounded: %v", elapsed)
	}

	deliverySawDeadline := false
	startedAt = time.Now()
	runBoundedDeliveryCycle(context.Background(), timeout, time.Now().UTC(), func(ctx context.Context, _ time.Time) {
		if _, ok := ctx.Deadline(); !ok {
			t.Fatal("delivery cycle context has no deadline")
		}
		deliverySawDeadline = true
		<-ctx.Done()
	})
	if !deliverySawDeadline {
		t.Fatal("delivery cycle did not run")
	}
	if elapsed := time.Since(startedAt); elapsed > 500*time.Millisecond {
		t.Fatalf("delivery cycle timeout was not bounded: %v", elapsed)
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
