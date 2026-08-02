package store

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
)

func TestInitialSchemaUsesFinalTextEventIDs(t *testing.T) {
	for _, fragment := range []string{
		"event_id TEXT PRIMARY KEY",
		"sequence BIGINT NOT NULL CHECK (sequence BETWEEN 0 AND 9007199254740991)",
		"UNIQUE (patient_id, sensor_id, sequence)",
		"measurement_id TEXT REFERENCES measurements(event_id)",
		"lease_token TEXT",
		"lease_expires_at TIMESTAMPTZ",
		"token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32)",
		"CREATE TABLE IF NOT EXISTS family_sessions",
		"expires_at TIMESTAMPTZ",
		"devices_active_token_hash_idx",
		"family_sessions_active_token_hash_idx",
		"backend_binding_id TEXT NOT NULL UNIQUE",
		"credential_revision BIGINT NOT NULL CHECK (credential_revision BETWEEN 1 AND 9007199254740991)",
		"devices_credential_revision_uniq",
	} {
		if !strings.Contains(initialSchema, fragment) {
			t.Fatalf("initial schema must contain %q", fragment)
		}
	}
	if strings.Contains(initialSchema, "ALTER COLUMN event_id") {
		t.Fatal("initial project schema must define the final event ID directly")
	}
	if strings.Contains(strings.ToLower(initialSchema), "create table users") {
		t.Fatal("initial project schema must not contain an unrelated users table")
	}
}

func TestDeviceBindingCredentialRevisionUsesJSONSafeIntegerBoundary(t *testing.T) {
	base := DeviceBinding{
		DeviceID:         "00000000-0000-4000-8000-000000000201",
		BackendBindingID: "backend-binding-1",
		CredentialID:     "credential-1",
	}
	for _, revision := range []int64{1, MaxCredentialRevision} {
		binding := base
		binding.CredentialRevision = revision
		if err := binding.Validate(); err != nil {
			t.Fatalf("valid credential revision %d was rejected: %v", revision, err)
		}
	}
	for _, revision := range []int64{0, -1, MaxCredentialRevision + 1} {
		binding := base
		binding.CredentialRevision = revision
		if err := binding.Validate(); err == nil {
			t.Fatalf("invalid credential revision %d was accepted", revision)
		}
	}
}

func TestMemoryCanonicalizesProvisionedUUIDsLikePostgres(t *testing.T) {
	values := NewMemory()
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID:     "AAAAAAAA-0000-4000-8000-000000000101",
		PatientID:       "BBBBBBBB-0000-4000-8000-000000000001",
		DeviceID:        "CCCCCCCC-0000-4000-8000-000000000201",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "DDDDDDDD-0000-4000-8000-000000000301",
		FamilyTokenHash: HashAccessToken("family-token"),
	})
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	device, err := values.ResolveActiveDevice(context.Background(), identity.DeviceTokenHash, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if device.ID != strings.ToLower(identity.DeviceID) || device.PatientID != strings.ToLower(identity.PatientID) {
		t.Fatalf("memory UUIDs are not canonical: %#v", device)
	}
	session, err := values.ResolveActiveFamilySession(context.Background(), identity.FamilyTokenHash, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if session.ID != strings.ToLower(identity.FamilySessionID) || session.HouseholdID != strings.ToLower(identity.HouseholdID) {
		t.Fatalf("memory family UUIDs are not canonical: %#v", session)
	}
}

func testBootstrapIdentity(identity BootstrapIdentity) BootstrapIdentity {
	identity.BackendBindingID = "backend-binding-1"
	identity.CredentialID = "credential-1"
	identity.CredentialRevision = 1
	return identity
}

func activateMemoryMonitoring(t *testing.T, values *Memory, patientID string, at time.Time) {
	t.Helper()
	_, err := values.ProcessMeasurement(context.Background(), domain.Measurement{
		EventID: "activation-" + patientID, PatientID: patientID, SensorID: "activation-sensor",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at, PhoneTime: at, ReceivedAt: at,
		GlucoseMgDL: 110, Quality: domain.QualityValid,
	}, nil, func(alerts.State, domain.Measurement) []alerts.Change { return nil })
	if err != nil {
		t.Fatal(err)
	}
}

func TestMemoryResolvesActiveDeviceAndFamilySessionByTokenDigest(t *testing.T) {
	values := NewMemory()
	ctx := context.Background()
	now := time.Date(2026, 8, 2, 9, 0, 0, 0, time.UTC)
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
	})
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}

	device, err := values.ResolveActiveDevice(ctx, HashAccessToken("device-token"), now)
	if err != nil {
		t.Fatal(err)
	}
	if device.ID != identity.DeviceID || device.PatientID != identity.PatientID ||
		device.BackendBindingID != identity.BackendBindingID ||
		device.CredentialID != identity.CredentialID ||
		device.CredentialRevision != identity.CredentialRevision {
		t.Fatalf("wrong device access: %#v", device)
	}
	family, err := values.ResolveActiveFamilySession(ctx, HashAccessToken("family-token"), now)
	if err != nil {
		t.Fatal(err)
	}
	if family.ID != identity.FamilySessionID || family.HouseholdID != identity.HouseholdID {
		t.Fatalf("wrong family access: %#v", family)
	}
	allowed, err := values.HouseholdCanAccessPatient(ctx, family.HouseholdID, identity.PatientID)
	if err != nil || !allowed {
		t.Fatalf("own patient access: allowed=%v err=%v", allowed, err)
	}
}

func TestMemoryRejectsUnknownRevokedExpiredAndCrossRoleTokens(t *testing.T) {
	values := NewMemory()
	ctx := context.Background()
	now := time.Date(2026, 8, 2, 9, 0, 0, 0, time.UTC)
	expiresAt := now.Add(time.Minute)
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
		FamilySessionExpiresAt: &expiresAt,
	})
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}

	for name, digest := range map[string][]byte{
		"unknown":                    HashAccessToken("unknown-token"),
		"family token cannot ingest": identity.FamilyTokenHash,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := values.ResolveActiveDevice(ctx, digest, now); !errors.Is(err, ErrNotFound) {
				t.Fatalf("expected not found, got %v", err)
			}
		})
	}
	if _, err := values.ResolveActiveFamilySession(ctx, identity.DeviceTokenHash, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("device token authorized family endpoint: %v", err)
	}
	if _, err := values.ResolveActiveFamilySession(ctx, identity.FamilyTokenHash, expiresAt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired family session remained active: %v", err)
	}
	if err := values.RevokeDevice(ctx, identity.DeviceID, now); err != nil {
		t.Fatal(err)
	}
	if _, err := values.ResolveActiveDevice(ctx, identity.DeviceTokenHash, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked device remained active: %v", err)
	}
}

func TestBootstrapAccessRejectsReusingOneSecretAcrossRoles(t *testing.T) {
	values := NewMemory()
	digest := HashAccessToken("shared-token")
	err := values.BootstrapAccess(context.Background(), testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: digest,
		FamilySessionID: "family-session-1", FamilyTokenHash: digest,
	}))
	if err == nil {
		t.Fatal("same credential was accepted for device and family authorization")
	}
}

func TestMemoryBootstrapAccessIsExactInsertOnly(t *testing.T) {
	values := NewMemory()
	ctx := context.Background()
	expiresAt := time.Date(2026, 8, 3, 9, 0, 0, 0, time.UTC)
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", HouseholdName: "Первая семья",
		PatientID: "patient-1", PatientName: "Мама",
		DeviceID: "device-1", DeviceName: "Samsung",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
		FamilySessionExpiresAt: &expiresAt,
		TelegramRecipients:     []string{"chat-b", "chat-a", "chat-a"},
	})
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatalf("exact retry must be idempotent: %v", err)
	}

	changed := identity
	changedExpiry := expiresAt.Add(time.Hour)
	changed.FamilySessionExpiresAt = &changedExpiry
	if err := values.BootstrapAccess(ctx, changed); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("changed expiry was not rejected: %v", err)
	}
	changed = identity
	changed.DeviceName = "Другой телефон"
	if err := values.BootstrapAccess(ctx, changed); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("changed device identity was not rejected: %v", err)
	}
	changed = identity
	changed.CredentialRevision++
	if err := values.BootstrapAccess(ctx, changed); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("changed credential tuple was not rejected: %v", err)
	}
	changed = identity
	changed.TelegramRecipients = []string{"chat-a"}
	if err := values.BootstrapAccess(ctx, changed); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("changed household recipients were not rejected: %v", err)
	}

	recipients, err := values.TelegramRecipients(ctx, identity.PatientID)
	if err != nil || len(recipients) != 2 || recipients[0] != "chat-a" || recipients[1] != "chat-b" {
		t.Fatalf("wrong household recipients: recipients=%#v err=%v", recipients, err)
	}
}

func TestDeviceLastSeenChangesOnlyAfterSuccessfulAtomicIngest(t *testing.T) {
	values := NewMemory()
	ctx := context.Background()
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
	})
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}
	authAt := time.Date(2026, 8, 2, 9, 0, 0, 0, time.UTC)
	device, err := values.ResolveActiveDevice(ctx, identity.DeviceTokenHash, authAt)
	if err != nil {
		t.Fatal(err)
	}
	if values.devices[identity.DeviceID].LastSeenAt != nil {
		t.Fatal("read-only authentication changed last_seen_at")
	}

	measurement := storedMeasurement()
	measurement.PatientID = identity.PatientID
	measurement.ReceivedAt = authAt.Add(time.Minute)
	_, err = values.ProcessDeviceMeasurement(ctx, device, measurement, nil, func(alerts.State, domain.Measurement) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: domain.Alert{}}}
	})
	if err == nil {
		t.Fatal("injected invalid transaction unexpectedly succeeded")
	}
	if values.devices[identity.DeviceID].LastSeenAt != nil {
		t.Fatal("failed ingest changed last_seen_at")
	}

	duplicate, err := values.ProcessDeviceMeasurement(ctx, device, measurement, nil, alerts.NewEngine(alerts.DefaultThresholds()).PlanMeasurement)
	if err != nil || duplicate {
		t.Fatalf("successful ingest: duplicate=%v err=%v", duplicate, err)
	}
	lastSeen := values.devices[identity.DeviceID].LastSeenAt
	if lastSeen == nil || !lastSeen.Equal(measurement.ReceivedAt) {
		t.Fatalf("successful ingest did not set last_seen_at: %v", lastSeen)
	}
}

func TestStaleDeviceCredentialTupleCannotCommitOrAdvanceLastSeen(t *testing.T) {
	values := NewMemory()
	ctx := context.Background()
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
	})
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}
	device, err := values.ResolveActiveDevice(ctx, identity.DeviceTokenHash, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	stale := device
	stale.CredentialRevision++
	measurement := storedMeasurement()
	measurement.PatientID = identity.PatientID
	duplicate, err := values.ProcessDeviceMeasurement(
		ctx, stale, measurement, []string{"family-chat"}, alerts.NewEngine(alerts.DefaultThresholds()).PlanMeasurement,
	)
	if duplicate || !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("stale credential tuple: duplicate=%v err=%v", duplicate, err)
	}
	if values.devices[identity.DeviceID].LastSeenAt != nil {
		t.Fatal("stale credential tuple advanced last_seen_at")
	}
	if _, err := values.Latest(ctx, identity.PatientID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stale credential tuple stored a measurement: %v", err)
	}
	if _, active := values.monitoringStarted[identity.PatientID]; active {
		t.Fatal("stale credential tuple activated monitoring")
	}
}

func TestMemoryDumpDoesNotContainRawAccessTokens(t *testing.T) {
	values := NewMemory()
	deviceToken := "raw-device-secret-that-must-not-appear"
	familyToken := "raw-family-secret-that-must-not-appear"
	if err := values.BootstrapAccess(context.Background(), testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken(deviceToken),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken(familyToken),
	})); err != nil {
		t.Fatal(err)
	}
	dump := fmt.Sprintf("%#v", values)
	if strings.Contains(dump, deviceToken) || strings.Contains(dump, familyToken) {
		t.Fatal("memory store dump exposed a raw access token")
	}
}

func TestMemoryProductionAccessFailsClosedUntilEachPatientIsReachable(t *testing.T) {
	now := time.Date(2026, 8, 2, 9, 0, 0, 0, time.UTC)
	values := NewMemory()
	if err := values.ValidateProductionAccess(context.Background(), now); !errors.Is(err, ErrAccessNotProvisioned) {
		t.Fatalf("empty store passed production readiness: %v", err)
	}
	missingRecipient := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
	})
	if err := values.BootstrapAccess(context.Background(), missingRecipient); err != nil {
		t.Fatal(err)
	}
	if err := values.ValidateProductionAccess(context.Background(), now); !errors.Is(err, ErrAccessNotProvisioned) {
		t.Fatalf("patient without Telegram recipient passed production readiness: %v", err)
	}

	values = NewMemory()
	identity := missingRecipient
	identity.TelegramRecipients = []string{"family-chat"}
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	if err := values.ValidateProductionAccess(context.Background(), now); err != nil {
		t.Fatalf("complete access graph failed readiness: %v", err)
	}
	if err := values.RevokeDevice(context.Background(), identity.DeviceID, now); err != nil {
		t.Fatal(err)
	}
	if err := values.ValidateProductionAccess(context.Background(), now); !errors.Is(err, ErrAccessNotProvisioned) {
		t.Fatalf("revoked only device passed readiness: %v", err)
	}
}

func TestMemoryReportsConfiguredTelegramRecipients(t *testing.T) {
	values := NewMemory()
	hasRecipients, err := values.HasTelegramRecipients(context.Background())
	if err != nil || hasRecipients {
		t.Fatalf("empty store recipients: configured=%v err=%v", hasRecipients, err)
	}
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-telegram", PatientID: "patient-telegram", DeviceID: "device-telegram",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-telegram", FamilyTokenHash: HashAccessToken("family-token"),
		TelegramRecipients: []string{"  ", "123456789"},
	})
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	hasRecipients, err = values.HasTelegramRecipients(context.Background())
	if err != nil || !hasRecipients {
		t.Fatalf("configured recipients were not reported: configured=%v err=%v", hasRecipients, err)
	}
}

func TestMemoryDeviceLastSeenIsMonotonicUnderConcurrentOutOfOrderIngest(t *testing.T) {
	values := NewMemory()
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-last-seen", PatientID: "patient-last-seen", DeviceID: "device-last-seen",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-last-seen", FamilyTokenHash: HashAccessToken("family-token"),
	})
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	device, err := values.ResolveActiveDevice(context.Background(), identity.DeviceTokenHash, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	const count = 24
	var wg sync.WaitGroup
	errorsByCall := make(chan error, count)
	for index := 0; index < count; index++ {
		index := index
		wg.Add(1)
		go func() {
			defer wg.Done()
			measurement := storedMeasurement()
			measurement.EventID = fmt.Sprintf("last-seen-%02d", index)
			measurement.PatientID = identity.PatientID
			measurement.Sequence = uint64(index)
			measurement.ReceivedAt = base.Add(time.Duration(index) * time.Minute)
			_, processErr := values.ProcessDeviceMeasurement(
				context.Background(), device, measurement, nil,
				func(alerts.State, domain.Measurement) []alerts.Change { return nil },
			)
			errorsByCall <- processErr
		}()
	}
	wg.Wait()
	close(errorsByCall)
	for processErr := range errorsByCall {
		if processErr != nil {
			t.Fatal(processErr)
		}
	}
	want := base.Add((count - 1) * time.Minute)
	lastSeen := values.devices[identity.DeviceID].LastSeenAt
	if lastSeen == nil || !lastSeen.Equal(want) {
		t.Fatalf("last_seen_at moved backwards: got=%v want=%v", lastSeen, want)
	}
}

func TestProvisionedPatientDoesNotStartSignalLossMonitoring(t *testing.T) {
	values := NewMemory()
	identity := testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", DeviceID: "device-1",
		DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
		TelegramRecipients: []string{"family-chat"},
	})
	if err := values.BootstrapAccess(context.Background(), identity); err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 8, 2, 9, 0, 0, 0, time.UTC)
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	if err := values.ProcessStaleness(
		context.Background(), identity.PatientID, base.Add(24*time.Hour),
		[]string{"family-chat"}, engine.PlanStaleness,
	); err != nil {
		t.Fatal(err)
	}
	open, err := values.OpenAlerts(context.Background(), identity.PatientID)
	if err != nil || len(open) != 0 {
		t.Fatalf("never-started patient got signal loss: alerts=%#v err=%v", open, err)
	}
	if _, active := values.monitoringStarted[identity.PatientID]; active {
		t.Fatal("staleness scheduler implicitly activated monitoring")
	}
}

func TestMemoryRejectsDifferentEventForSameSensorSequence(t *testing.T) {
	values := NewMemory()
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	original := storedMeasurement()
	if duplicate, err := values.ProcessMeasurement(context.Background(), original, nil, engine.PlanMeasurement); err != nil || duplicate {
		t.Fatalf("first ingest: duplicate=%v err=%v", duplicate, err)
	}

	conflict := original
	conflict.EventID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	conflict.SensorTime = conflict.SensorTime.Add(time.Minute)
	duplicate, err := values.ProcessMeasurement(context.Background(), conflict, nil, engine.PlanMeasurement)
	if duplicate || !errors.Is(err, ErrEventConflict) {
		t.Fatalf("same sensor sequence was accepted under another event: duplicate=%v err=%v", duplicate, err)
	}
	valuesForPatient, err := values.List(
		context.Background(), original.PatientID,
		original.SensorTime.Add(-time.Minute), conflict.SensorTime.Add(time.Minute),
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(valuesForPatient) != 1 || valuesForPatient[0].EventID != original.EventID {
		t.Fatalf("sequence conflict changed history: %#v", valuesForPatient)
	}
}

func TestMemoryAtomicIngestDistinguishesExactRetryFromEventConflict(t *testing.T) {
	values := NewMemory()
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	original := storedMeasurement()

	duplicate, err := values.ProcessMeasurement(context.Background(), original, nil, engine.PlanMeasurement)
	if err != nil || duplicate {
		t.Fatalf("first ingest: duplicate=%v err=%v", duplicate, err)
	}

	exactRetry := original
	exactRetry.ReceivedAt = original.ReceivedAt.Add(time.Minute)
	duplicate, err = values.ProcessMeasurement(context.Background(), exactRetry, nil, engine.PlanMeasurement)
	if err != nil || !duplicate {
		t.Fatalf("exact retry: duplicate=%v err=%v", duplicate, err)
	}

	conflict := original
	conflict.GlucoseMgDL++
	duplicate, err = values.ProcessMeasurement(context.Background(), conflict, nil, engine.PlanMeasurement)
	if duplicate || !errors.Is(err, ErrEventConflict) {
		t.Fatalf("conflicting retry: duplicate=%v err=%v", duplicate, err)
	}

	latest, err := values.Latest(context.Background(), original.PatientID)
	if err != nil {
		t.Fatal(err)
	}
	if latest.GlucoseMgDL != original.GlucoseMgDL {
		t.Fatalf("conflict changed stored value: got %d want %d", latest.GlucoseMgDL, original.GlucoseMgDL)
	}
}

func TestMemoryOpenAlertsReflectsAcknowledgementAndClosure(t *testing.T) {
	values := NewMemory()
	at := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	alert := domain.Alert{
		ID: "alert-1", PatientID: "patient-1", Kind: domain.AlertLow, OpenedAt: at,
	}
	activateMemoryMonitoring(t, values, alert.PatientID, at)
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, at, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}

	acknowledgedAt := at.Add(time.Minute)
	if err := values.AcknowledgeAlert(context.Background(), alert.PatientID, alert.ID, acknowledgedAt); err != nil {
		t.Fatal(err)
	}
	open, err := values.OpenAlerts(context.Background(), alert.PatientID)
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 1 || open[0].AcknowledgedAt == nil || !open[0].AcknowledgedAt.Equal(acknowledgedAt) {
		t.Fatalf("acknowledged open alert was not restored: %#v", open)
	}

	closedAt := at.Add(2 * time.Minute)
	alert.ClosedAt = &closedAt
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, closedAt, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Closed, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	open, err = values.OpenAlerts(context.Background(), alert.PatientID)
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 0 {
		t.Fatalf("closed alert remained open: %#v", open)
	}
}

func TestMemoryAtomicProcessingRejectsMeasurementWithInvalidAlertPlan(t *testing.T) {
	values := NewMemory()
	value := storedMeasurement()
	planner := func(alerts.State, domain.Measurement) []alerts.Change {
		return []alerts.Change{{
			Type: alerts.Opened,
			Alert: domain.Alert{
				ID: "alert-invalid", PatientID: "different-patient",
				Kind: domain.AlertLow, OpenedAt: value.ReceivedAt,
			},
		}}
	}

	duplicate, err := values.ProcessMeasurement(context.Background(), value, []string{"family-chat"}, planner)
	if duplicate || !errors.Is(err, ErrInvalidAlertPlan) {
		t.Fatalf("invalid transaction: duplicate=%v err=%v", duplicate, err)
	}
	if _, err := values.Latest(context.Background(), value.PatientID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("measurement survived rejected alert transaction: %v", err)
	}
	open, err := values.OpenAlerts(context.Background(), value.PatientID)
	if err != nil || len(open) != 0 {
		t.Fatalf("alert survived rejected transaction: alerts=%#v err=%v", open, err)
	}
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	if err := values.ProcessStaleness(
		context.Background(), value.PatientID, value.ReceivedAt.Add(11*time.Minute), nil, engine.PlanStaleness,
	); err != nil {
		t.Fatal(err)
	}
	open, err = values.OpenAlerts(context.Background(), value.PatientID)
	if err != nil || len(open) != 0 {
		t.Fatalf("rejected transaction leaked monitoring baseline: alerts=%#v err=%v", open, err)
	}
}

func TestMemoryConcurrentProcessingCreatesOneOpenAlertAndDelivery(t *testing.T) {
	values := NewMemory()
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	base := time.Now().UTC().Add(-time.Minute)
	activateMemoryMonitoring(t, values, "patient-1", base)

	const count = 64
	var wg sync.WaitGroup
	errorsSeen := make(chan error, count)
	for index := 0; index < count; index++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			value := storedMeasurement()
			value.EventID = fmt.Sprintf("%064x", index+1)
			value.SensorTime = base.Add(time.Duration(index) * time.Second)
			value.PhoneTime = value.SensorTime
			value.ReceivedAt = base.Add(time.Minute)
			value.GlucoseMgDL = 55
			value.Sequence = uint64(index)
			_, err := values.ProcessMeasurement(
				context.Background(), value, []string{"family-chat"}, engine.PlanMeasurement,
			)
			if err != nil {
				errorsSeen <- err
			}
		}(index)
	}
	wg.Wait()
	close(errorsSeen)
	for err := range errorsSeen {
		t.Errorf("concurrent transaction failed: %v", err)
	}

	open, err := values.OpenAlerts(context.Background(), "patient-1")
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 1 || open[0].Kind != domain.AlertLow {
		t.Fatalf("expected one durable low alert, got %#v", open)
	}
	deliveries, err := values.ClaimDueAlertDeliveries(
		context.Background(), base.Add(time.Hour), count, "inspect-delivery", base.Add(2*time.Hour),
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(deliveries) != 1 || deliveries[0].Alert.ID != open[0].ID {
		t.Fatalf("expected one matching delivery, got %#v", deliveries)
	}
}

func TestMemoryConcurrentSameEventIsExactRetryOrConflict(t *testing.T) {
	values := NewMemory()
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	original := storedMeasurement()
	conflict := original
	conflict.GlucoseMgDL++

	results := make(chan error, 2)
	var wg sync.WaitGroup
	for _, value := range []domain.Measurement{original, conflict} {
		wg.Add(1)
		go func(value domain.Measurement) {
			defer wg.Done()
			_, err := values.ProcessMeasurement(context.Background(), value, nil, engine.PlanMeasurement)
			results <- err
		}(value)
	}
	wg.Wait()
	close(results)
	var success, conflicts int
	for err := range results {
		switch {
		case err == nil:
			success++
		case errors.Is(err, ErrEventConflict):
			conflicts++
		default:
			t.Fatalf("unexpected result: %v", err)
		}
	}
	if success != 1 || conflicts != 1 {
		t.Fatalf("success=%d conflicts=%d", success, conflicts)
	}
}

func TestMemoryDeliveryLeasePreventsConcurrentClaimAndExpires(t *testing.T) {
	values := NewMemory()
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	if err := values.BootstrapAccess(context.Background(), testBootstrapIdentity(BootstrapIdentity{
		HouseholdID: "household-1", PatientID: "patient-1", PatientName: "  Мама\n Иванова  ",
		DeviceID: "device-1", DeviceTokenHash: HashAccessToken("device-token"),
		FamilySessionID: "family-session-1", FamilyTokenHash: HashAccessToken("family-token"),
		TelegramRecipients: []string{"family-chat"},
	})); err != nil {
		t.Fatal(err)
	}
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000020", PatientID: "patient-1",
		Kind: domain.AlertLow, OpenedAt: now,
	}
	activateMemoryMonitoring(t, values, alert.PatientID, now)
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, now, []string{"family-chat"}, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}

	first, err := values.ClaimDueAlertDeliveries(context.Background(), now, 10, "lease-one", now.Add(time.Minute))
	if err != nil || len(first) != 1 {
		t.Fatalf("first claim: deliveries=%#v err=%v", first, err)
	}
	if first[0].PatientDisplayName != "Мама Иванова" {
		t.Fatalf("delivery lost the patient display name: %#v", first[0])
	}
	second, err := values.ClaimDueAlertDeliveries(context.Background(), now, 10, "lease-two", now.Add(time.Minute))
	if err != nil || len(second) != 0 {
		t.Fatalf("active lease was claimed twice: deliveries=%#v err=%v", second, err)
	}
	if err := values.MarkAlertDeliverySent(context.Background(), first[0].ID, "lease-one", now.Add(time.Minute)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired lease marked delivery sent: %v", err)
	}
	recovered, err := values.ClaimDueAlertDeliveries(context.Background(), now.Add(time.Minute), 10, "lease-two", now.Add(2*time.Minute))
	if err != nil || len(recovered) != 1 || recovered[0].ID != first[0].ID {
		t.Fatalf("expired lease was not recovered: deliveries=%#v err=%v", recovered, err)
	}
	if err := values.MarkAlertDeliverySent(context.Background(), first[0].ID, "lease-one", now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stale lease marked delivery sent: %v", err)
	}
	if err := values.MarkAlertDeliveryFailed(
		context.Background(), first[0].ID, "lease-two", now.Add(90*time.Second), now.Add(2*time.Minute), "temporary",
	); err != nil {
		t.Fatalf("current lease could not release retry: %v", err)
	}
	third, err := values.ClaimDueAlertDeliveries(context.Background(), now.Add(2*time.Minute), 10, "lease-three", now.Add(3*time.Minute))
	if err != nil || len(third) != 1 {
		t.Fatalf("released retry was not claimable: deliveries=%#v err=%v", third, err)
	}
}

func TestMemoryAcknowledgeIsPatientScoped(t *testing.T) {
	values := NewMemory()
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000020", PatientID: "patient-1",
		Kind: domain.AlertLow, OpenedAt: now,
	}
	activateMemoryMonitoring(t, values, alert.PatientID, now)
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, now, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	if err := values.AcknowledgeAlert(context.Background(), "patient-2", alert.ID, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("another patient acknowledged alert: %v", err)
	}
	if err := values.AcknowledgeAlert(context.Background(), alert.PatientID, alert.ID, now); err != nil {
		t.Fatalf("owner patient could not acknowledge alert: %v", err)
	}
}

func TestMemoryPatientSnapshotCannotObserveHalfCommittedMeasurementAlert(t *testing.T) {
	values := NewMemory()
	measurement := storedMeasurement()
	measurement.GlucoseMgDL = 55
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000032", PatientID: measurement.PatientID,
		Kind: domain.AlertLow, OpenedAt: measurement.ReceivedAt,
	}
	plannerEntered := make(chan struct{})
	releasePlanner := make(chan struct{})
	processDone := make(chan error, 1)
	go func() {
		_, err := values.ProcessMeasurement(context.Background(), measurement, nil, func(alerts.State, domain.Measurement) []alerts.Change {
			close(plannerEntered)
			<-releasePlanner
			return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
		})
		processDone <- err
	}()
	<-plannerEntered
	type snapshotResult struct {
		value domain.PatientSnapshot
		err   error
	}
	snapshotDone := make(chan snapshotResult, 1)
	go func() {
		value, err := values.PatientSnapshot(context.Background(), measurement.PatientID)
		snapshotDone <- snapshotResult{value: value, err: err}
	}()
	select {
	case result := <-snapshotDone:
		t.Fatalf("snapshot escaped transaction lock: %#v err=%v", result.value, result.err)
	case <-time.After(20 * time.Millisecond):
	}
	close(releasePlanner)
	if err := <-processDone; err != nil {
		t.Fatal(err)
	}
	result := <-snapshotDone
	if result.err != nil || result.value.Latest == nil || result.value.Latest.EventID != measurement.EventID ||
		len(result.value.OpenAlerts) != 1 || result.value.OpenAlerts[0].ID != alert.ID {
		t.Fatalf("snapshot did not contain one committed state: %#v err=%v", result.value, result.err)
	}
}

func TestMemoryAlertStateAndSnapshotIgnoreNonValidMeasurements(t *testing.T) {
	values := NewMemory()
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	valid := storedMeasurement()
	valid.EventID = fmt.Sprintf("%064x", 701)
	valid.SensorTime = base
	valid.PhoneTime = base
	valid.ReceivedAt = base
	valid.Quality = domain.QualityValid
	if _, err := values.ProcessMeasurement(context.Background(), valid, nil, func(alerts.State, domain.Measurement) []alerts.Change {
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	warming := valid
	warming.EventID = fmt.Sprintf("%064x", 702)
	warming.SensorTime = base.Add(20 * time.Minute)
	warming.PhoneTime = warming.SensorTime
	warming.ReceivedAt = warming.SensorTime
	warming.Quality = domain.QualityWarmingUp
	warming.Sequence = valid.Sequence + 1
	if _, err := values.ProcessMeasurement(context.Background(), warming, nil, func(alerts.State, domain.Measurement) []alerts.Change {
		return nil
	}); err != nil {
		t.Fatal(err)
	}

	var observed alerts.State
	if err := values.ProcessStaleness(context.Background(), valid.PatientID, base.Add(21*time.Minute), nil, func(state alerts.State, _ string, _ time.Time) []alerts.Change {
		observed = state
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if !observed.LatestAt.Equal(valid.SensorTime) {
		t.Fatalf("alert state used non-valid time: got %s want %s", observed.LatestAt, valid.SensorTime)
	}
	snapshot, err := values.PatientSnapshot(context.Background(), valid.PatientID)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Latest == nil || snapshot.Latest.EventID != valid.EventID {
		t.Fatalf("snapshot exposed non-valid measurement as latest: %#v", snapshot.Latest)
	}
}

func TestMemorySnapshotIsMissingUntilFirstValidMeasurement(t *testing.T) {
	values := NewMemory()
	value := storedMeasurement()
	value.Quality = domain.QualityDegraded
	if _, err := values.ProcessMeasurement(context.Background(), value, nil, func(alerts.State, domain.Measurement) []alerts.Change {
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	snapshot, err := values.PatientSnapshot(context.Background(), value.PatientID)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Latest != nil {
		t.Fatalf("non-valid measurement became a product snapshot: %#v", snapshot.Latest)
	}
}

func storedMeasurement() domain.Measurement {
	at := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	return domain.Measurement{
		EventID:   "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		PatientID: "patient-1", SensorID: "sensor-1",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at,
		PhoneTime: at, ReceivedAt: at.Add(time.Second), GlucoseMgDL: 110,
		TrendMgDLPerMinute: -0.2, Quality: domain.QualityValid, Sequence: 42,
	}
}
