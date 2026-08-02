package store

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
)

func TestPostgresFreshSchemaIsCurrentAndIdempotent(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	admin, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schemaName := "fresh_" + strings.ReplaceAll(testUUID(t), "-", "")
	if _, err := admin.pool.Exec(ctx, `CREATE SCHEMA "`+schemaName+`"`); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = admin.pool.Exec(cleanupCtx, `DROP SCHEMA "`+schemaName+`" CASCADE`)
	}()
	parsedURL, err := url.Parse(databaseURL)
	if err != nil || parsedURL.Scheme == "" {
		t.Fatalf("TEST_DATABASE_URL must be a PostgreSQL URL for isolated schema test: %v", err)
	}
	query := parsedURL.Query()
	query.Set("search_path", schemaName)
	parsedURL.RawQuery = query.Encode()
	values, err := NewPostgres(ctx, parsedURL.String())
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatalf("fresh schema initialization is not idempotent: %v", err)
	}

	var requiredColumns int
	if err := values.pool.QueryRow(ctx, `
		SELECT count(*) FROM information_schema.columns
		WHERE table_schema=$1 AND table_name='devices'
		  AND column_name IN ('backend_binding_id','credential_id','credential_revision')
		  AND is_nullable='NO'`, schemaName).Scan(&requiredColumns); err != nil {
		t.Fatal(err)
	}
	if requiredColumns != 3 {
		t.Fatalf("fresh devices schema has %d required binding columns, want 3", requiredColumns)
	}
	var deviceTokenNullable bool
	if err := values.pool.QueryRow(ctx, `
		SELECT is_nullable='YES' FROM information_schema.columns
		WHERE table_schema=$1 AND table_name='devices' AND column_name='token_hash'`, schemaName).Scan(&deviceTokenNullable); err != nil {
		t.Fatal(err)
	}
	if !deviceTokenNullable {
		t.Fatal("fresh devices schema cannot represent a safe pending activation without a bearer token")
	}
	var hasFamilySessions, hasFamilyWebSessions, hasDeviceActivations bool
	if err := values.pool.QueryRow(ctx, `
		SELECT to_regclass('family_sessions') IS NOT NULL,
		       to_regclass('family_web_sessions') IS NOT NULL,
		       to_regclass('device_activation_codes') IS NOT NULL`).Scan(
		&hasFamilySessions, &hasFamilyWebSessions, &hasDeviceActivations,
	); err != nil {
		t.Fatal(err)
	}
	if !hasFamilySessions {
		t.Fatal("fresh v1 schema is missing family_sessions")
	}
	if !hasFamilyWebSessions {
		t.Fatal("fresh v1 schema is missing family_web_sessions")
	}
	if !hasDeviceActivations {
		t.Fatal("fresh v1 schema is missing device_activation_codes")
	}
	hasRecipients, err := values.HasTelegramRecipients(ctx)
	if err != nil || hasRecipients {
		t.Fatalf("fresh database recipients: configured=%v err=%v", hasRecipients, err)
	}

	householdID, patientID := testUUID(t), testUUID(t)
	if _, err := values.pool.Exec(ctx, `INSERT INTO households (id,name) VALUES ($1,'fresh-family')`, householdID); err != nil {
		t.Fatal(err)
	}
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'fresh-patient')`, patientID, householdID); err != nil {
		t.Fatal(err)
	}
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO family_members (id,household_id,email,display_name,role,telegram_chat_id)
		VALUES ($1,$2,'fresh@example.invalid','Fresh relative','relative','123456789')`, testUUID(t), householdID); err != nil {
		t.Fatal(err)
	}
	hasRecipients, err = values.HasTelegramRecipients(ctx)
	if err != nil || !hasRecipients {
		t.Fatalf("database Telegram recipient was not detected: configured=%v err=%v", hasRecipients, err)
	}
	insertDevice := func(deviceID string, revision int64) error {
		_, insertErr := values.pool.Exec(ctx, `
			INSERT INTO devices (
				id,patient_id,name,token_hash,backend_binding_id,credential_id,credential_revision
			) VALUES ($1,$2,'fresh-device',$3,$4,$5,$6)`,
			deviceID, patientID, HashAccessToken("device-token-"+deviceID),
			"binding-"+deviceID, "credential-"+deviceID, revision,
		)
		return insertErr
	}
	if err := insertDevice(testUUID(t), MaxCredentialRevision); err != nil {
		t.Fatalf("fresh schema rejected maximum JSON-safe credential revision: %v", err)
	}
	if err := insertDevice(testUUID(t), MaxCredentialRevision+1); err == nil {
		t.Fatal("fresh schema accepted credential revision outside JSON safe-integer range")
	}
}

func TestPostgresAtomicAlertTransactions(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	householdID := testUUID(t)
	patientID := testUUID(t)
	if _, err := values.pool.Exec(ctx, `INSERT INTO households (id,name) VALUES ($1,'atomic-test')`, householdID); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, householdID)
	}()
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'atomic-patient')`,
		patientID, householdID,
	); err != nil {
		t.Fatal(err)
	}

	base := time.Now().UTC().Truncate(time.Millisecond)
	activatePostgresMonitoring(t, ctx, values, patientID, base.Add(-time.Minute), fmt.Sprintf("%064x", 99_001))
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	measurement := postgresMeasurement(patientID, fmt.Sprintf("%064x", 1), base, 55, 1)
	duplicate, err := values.ProcessMeasurement(ctx, measurement, []string{"family-chat"}, engine.PlanMeasurement)
	if err != nil || duplicate {
		t.Fatalf("first transaction: duplicate=%v err=%v", duplicate, err)
	}
	open, err := values.OpenAlerts(ctx, patientID)
	if err != nil || len(open) != 1 || open[0].Kind != domain.AlertLow {
		t.Fatalf("committed low alert missing: alerts=%#v err=%v", open, err)
	}
	var deliveryID, deliveryAlertID string
	if err := values.pool.QueryRow(ctx, `
		SELECT d.id, d.alert_id FROM alert_deliveries d
		JOIN alerts a ON a.id=d.alert_id WHERE a.patient_id=$1`, patientID).Scan(&deliveryID, &deliveryAlertID); err != nil {
		t.Fatal(err)
	}
	if deliveryAlertID != open[0].ID {
		t.Fatalf("committed delivery points to %s, want %s", deliveryAlertID, open[0].ID)
	}
	claimAt := base.Add(time.Minute)
	claimed, err := values.ClaimDueAlertDeliveries(ctx, claimAt, 10, "lease-one", claimAt.Add(time.Minute))
	if err != nil || len(claimed) != 1 || claimed[0].ID != deliveryID {
		t.Fatalf("first delivery claim: deliveries=%#v err=%v", claimed, err)
	}
	if claimed[0].PatientDisplayName != "atomic-patient" {
		t.Fatalf("PostgreSQL delivery lost patients.display_name: %#v", claimed[0])
	}
	claimedAgain, err := values.ClaimDueAlertDeliveries(ctx, claimAt, 10, "lease-two", claimAt.Add(time.Minute))
	if err != nil || len(claimedAgain) != 0 {
		t.Fatalf("active PostgreSQL lease was claimed twice: deliveries=%#v err=%v", claimedAgain, err)
	}
	if err := values.MarkAlertDeliverySent(ctx, deliveryID, "lease-one", claimAt.Add(time.Minute)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired PostgreSQL lease marked delivery sent: %v", err)
	}
	recovered, err := values.ClaimDueAlertDeliveries(ctx, claimAt.Add(time.Minute), 10, "lease-two", claimAt.Add(2*time.Minute))
	if err != nil || len(recovered) != 1 || recovered[0].ID != deliveryID {
		t.Fatalf("expired PostgreSQL lease was not recovered: deliveries=%#v err=%v", recovered, err)
	}
	if err := values.MarkAlertDeliverySent(ctx, deliveryID, "lease-one", base); !errors.Is(err, ErrNotFound) {
		t.Fatalf("stale PostgreSQL lease marked delivery sent: %v", err)
	}
	if err := values.MarkAlertDeliveryFailed(
		ctx, deliveryID, "lease-two", claimAt.Add(90*time.Second), claimAt.Add(2*time.Minute), "temporary",
	); err != nil {
		t.Fatalf("current PostgreSQL lease could not release retry: %v", err)
	}

	retry := measurement
	retry.ReceivedAt = retry.ReceivedAt.Add(time.Minute)
	duplicate, err = values.ProcessMeasurement(ctx, retry, nil, func(alerts.State, domain.Measurement) []alerts.Change {
		t.Fatal("exact retry must not evaluate alerts")
		return nil
	})
	if err != nil || !duplicate {
		t.Fatalf("exact retry: duplicate=%v err=%v", duplicate, err)
	}
	conflict := measurement
	conflict.GlucoseMgDL++
	if duplicate, err = values.ProcessMeasurement(ctx, conflict, nil, engine.PlanMeasurement); duplicate || !errors.Is(err, ErrEventConflict) {
		t.Fatalf("event conflict: duplicate=%v err=%v", duplicate, err)
	}
	sequenceConflict := measurement
	sequenceConflict.EventID = fmt.Sprintf("%064x", 9)
	sequenceConflict.SensorTime = sequenceConflict.SensorTime.Add(time.Second)
	if duplicate, err = values.ProcessMeasurement(ctx, sequenceConflict, nil, engine.PlanMeasurement); duplicate || !errors.Is(err, ErrEventConflict) {
		t.Fatalf("sensor sequence conflict: duplicate=%v err=%v", duplicate, err)
	}

	rollbackEventID := fmt.Sprintf("%064x", 2)
	rollbackMeasurement := postgresMeasurement(patientID, rollbackEventID, base.Add(time.Second), 110, 2)
	invalidDatabasePlan := func(alerts.State, domain.Measurement) []alerts.Change {
		return []alerts.Change{{
			Type: alerts.Opened,
			Alert: domain.Alert{
				ID: "not-a-postgres-uuid", PatientID: patientID,
				Kind: domain.AlertHigh, OpenedAt: base,
			},
		}}
	}
	if _, err := values.ProcessMeasurement(ctx, rollbackMeasurement, nil, invalidDatabasePlan); err == nil {
		t.Fatal("invalid alert write must fail the transaction")
	}
	var rollbackCount int
	if err := values.pool.QueryRow(ctx, `SELECT count(*) FROM measurements WHERE event_id=$1`, rollbackEventID).Scan(&rollbackCount); err != nil {
		t.Fatal(err)
	}
	if rollbackCount != 0 {
		t.Fatalf("measurement survived failed alert write: count=%d", rollbackCount)
	}

	acknowledgedAt := base.Add(2 * time.Minute)
	if err := values.AcknowledgeAlert(ctx, testUUID(t), open[0].ID, acknowledgedAt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("another patient acknowledged PostgreSQL alert: %v", err)
	}
	if err := values.AcknowledgeAlert(ctx, patientID, open[0].ID, acknowledgedAt); err != nil {
		t.Fatal(err)
	}
	recovery := postgresMeasurement(patientID, fmt.Sprintf("%064x", 3), base.Add(2*time.Second), 80, 3)
	if duplicate, err := values.ProcessMeasurement(ctx, recovery, nil, engine.PlanMeasurement); err != nil || duplicate {
		t.Fatalf("recovery transaction: duplicate=%v err=%v", duplicate, err)
	}
	if openAfterRecovery, err := values.OpenAlerts(ctx, patientID); err != nil || len(openAfterRecovery) != 0 {
		t.Fatalf("recovered alert remained open: alerts=%#v err=%v", openAfterRecovery, err)
	}
	var closedAt, storedAcknowledgedAt *time.Time
	if err := values.pool.QueryRow(ctx, `
		SELECT closed_at, acknowledged_at FROM alerts WHERE id=$1`, open[0].ID).Scan(&closedAt, &storedAcknowledgedAt); err != nil {
		t.Fatal(err)
	}
	if closedAt == nil || storedAcknowledgedAt == nil || !storedAcknowledgedAt.Equal(acknowledgedAt) {
		t.Fatalf("closure lost durable acknowledgement: closed=%v acknowledged=%v", closedAt, storedAcknowledgedAt)
	}
}

func TestPostgresSerializesConcurrentAlertEvaluation(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	householdID := testUUID(t)
	patientID := testUUID(t)
	if _, err := values.pool.Exec(ctx, `INSERT INTO households (id,name) VALUES ($1,'concurrency-test')`, householdID); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, householdID)
	}()
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'concurrent-patient')`,
		patientID, householdID,
	); err != nil {
		t.Fatal(err)
	}
	base := time.Now().UTC().Truncate(time.Millisecond).Add(-time.Minute)
	activatePostgresMonitoring(t, ctx, values, patientID, base, fmt.Sprintf("%064x", 99_002))
	engine := alerts.NewEngine(alerts.DefaultThresholds())

	const count = 32
	var wg sync.WaitGroup
	errorsSeen := make(chan error, count)
	for index := 0; index < count; index++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			value := postgresMeasurement(
				patientID,
				fmt.Sprintf("%064x", 1_000+index),
				base.Add(time.Duration(index)*time.Second),
				55,
				uint64(index),
			)
			_, err := values.ProcessMeasurement(ctx, value, []string{"family-chat"}, engine.PlanMeasurement)
			if err != nil {
				errorsSeen <- err
			}
		}(index)
	}
	wg.Wait()
	close(errorsSeen)
	for err := range errorsSeen {
		t.Errorf("concurrent process failed: %v", err)
	}
	open, err := values.OpenAlerts(ctx, patientID)
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 1 || open[0].Kind != domain.AlertLow {
		t.Fatalf("expected one open low alert, got %#v", open)
	}
	var deliveryCount int
	if err := values.pool.QueryRow(ctx, `
		SELECT count(*) FROM alert_deliveries d
		JOIN alerts a ON a.id=d.alert_id WHERE a.patient_id=$1`, patientID).Scan(&deliveryCount); err != nil {
		t.Fatal(err)
	}
	if deliveryCount != 1 {
		t.Fatalf("expected one delivery, got %d", deliveryCount)
	}
}

func TestPostgresSignalLossBaselineSurvivesRestart(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	householdID := testUUID(t)
	patientID := testUUID(t)
	if _, err := values.pool.Exec(ctx, `INSERT INTO households (id,name) VALUES ($1,'restart-test')`, householdID); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, householdID)
	}()
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'restart-patient')`,
		patientID, householdID,
	); err != nil {
		t.Fatal(err)
	}

	base := time.Now().UTC().Truncate(time.Millisecond)
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	activatePostgresMonitoring(t, ctx, values, patientID, base, fmt.Sprintf("%064x", 99_003))
	if err := values.ProcessStaleness(ctx, patientID, base.Add(11*time.Minute), []string{"family-chat"}, engine.PlanStaleness); err != nil {
		t.Fatal(err)
	}
	before, err := values.OpenAlerts(ctx, patientID)
	if err != nil || len(before) != 1 || before[0].Kind != domain.AlertSignalLoss {
		t.Fatalf("signal loss did not open: alerts=%#v err=%v", before, err)
	}

	// A process restart performs no activation write. The original durable
	// baseline and the existing signal-loss alert must both survive unchanged.
	if err := values.ProcessStaleness(ctx, patientID, base.Add(13*time.Minute), []string{"family-chat"}, engine.PlanStaleness); err != nil {
		t.Fatal(err)
	}
	after, err := values.OpenAlerts(ctx, patientID)
	if err != nil {
		t.Fatal(err)
	}
	if len(after) != 1 || after[0].ID != before[0].ID || after[0].Kind != domain.AlertSignalLoss {
		t.Fatalf("restart closed or duplicated signal loss: before=%#v after=%#v", before, after)
	}
}

func TestPostgresAccessTokensResolveMultipleFamiliesWithoutStoringPlaintext(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	now := time.Now().UTC().Truncate(time.Millisecond)
	first := BootstrapIdentity{
		HouseholdID: testUUID(t), PatientID: testUUID(t), DeviceID: testUUID(t),
		DeviceTokenHash:  HashAccessToken("first-device-token-0123456789abcdef"),
		BackendBindingID: "backend-binding-first", CredentialID: "credential-first", CredentialRevision: 1,
		FamilySessionID: testUUID(t), FamilyTokenHash: HashAccessToken("first-family-token-0123456789abcdef"),
		TelegramRecipients: []string{"first-family-chat"},
	}
	expiresAt := now.Add(time.Minute)
	second := BootstrapIdentity{
		HouseholdID: testUUID(t), PatientID: testUUID(t), DeviceID: testUUID(t),
		DeviceTokenHash:  HashAccessToken("second-device-token-0123456789abcdef"),
		BackendBindingID: "backend-binding-second", CredentialID: "credential-second", CredentialRevision: 2,
		FamilySessionID: testUUID(t), FamilyTokenHash: HashAccessToken("second-family-token-0123456789abcdef"),
		FamilySessionExpiresAt: &expiresAt,
		TelegramRecipients:     []string{"second-family-chat"},
	}
	for _, identity := range []BootstrapIdentity{first, second} {
		if err := values.BootstrapAccess(ctx, identity); err != nil {
			t.Fatal(err)
		}
		identity := identity
		defer func() {
			cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, identity.HouseholdID)
		}()
	}
	if err := values.ValidateProductionAccess(ctx, now); err != nil {
		rows, queryErr := values.pool.Query(ctx, `
			SELECT p.id,
			       EXISTS (SELECT 1 FROM devices d WHERE d.patient_id=p.id AND d.revoked_at IS NULL),
			       EXISTS (SELECT 1 FROM family_sessions fs WHERE fs.household_id=p.household_id
			               AND fs.revoked_at IS NULL AND (fs.expires_at IS NULL OR fs.expires_at > $1))
			FROM patients p ORDER BY p.id`, now)
		if queryErr != nil {
			t.Fatalf("complete PostgreSQL access graph failed readiness: %v; inspect=%v", err, queryErr)
		}
		defer rows.Close()
		var states []string
		for rows.Next() {
			var id string
			var hasDevice, hasSession bool
			if scanErr := rows.Scan(&id, &hasDevice, &hasSession); scanErr != nil {
				t.Fatal(scanErr)
			}
			states = append(states, fmt.Sprintf("%s:device=%v:session=%v", id, hasDevice, hasSession))
		}
		t.Fatalf("complete PostgreSQL access graph failed readiness: %v states=%v", err, states)
	}
	if err := values.ProcessStaleness(
		ctx, second.PatientID, now.Add(24*time.Hour), []string{"second-family-chat"},
		alerts.NewEngine(alerts.DefaultThresholds()).PlanStaleness,
	); err != nil {
		t.Fatal(err)
	}
	var inactiveStateCount, inactiveAlertCount int
	if err := values.pool.QueryRow(ctx, `
		SELECT
			(SELECT count(*) FROM patient_monitoring_state WHERE patient_id=$1),
			(SELECT count(*) FROM alerts WHERE patient_id=$1)`, second.PatientID).Scan(&inactiveStateCount, &inactiveAlertCount); err != nil {
		t.Fatal(err)
	}
	if inactiveStateCount != 0 || inactiveAlertCount != 0 {
		t.Fatalf("provisioning implicitly started PostgreSQL monitoring: state=%d alerts=%d", inactiveStateCount, inactiveAlertCount)
	}

	device, err := values.ResolveActiveDevice(ctx, second.DeviceTokenHash, now)
	if err != nil || device.ID != second.DeviceID || device.PatientID != second.PatientID ||
		device.BackendBindingID != second.BackendBindingID || device.CredentialID != second.CredentialID ||
		device.CredentialRevision != second.CredentialRevision {
		t.Fatalf("second device resolved incorrectly: access=%#v err=%v", device, err)
	}
	var lastSeenAt *time.Time
	if err := values.pool.QueryRow(ctx, `SELECT last_seen_at FROM devices WHERE id=$1`, second.DeviceID).Scan(&lastSeenAt); err != nil {
		t.Fatal(err)
	}
	if lastSeenAt != nil {
		t.Fatalf("read-only PostgreSQL auth changed last_seen_at: %v", lastSeenAt)
	}
	measurement := postgresMeasurement(second.PatientID, fmt.Sprintf("%064x", 88_001), now, 110, 1)
	staleDevice := device
	staleDevice.CredentialRevision++
	if _, err := values.ProcessDeviceMeasurement(
		ctx, staleDevice, measurement, nil, alerts.NewEngine(alerts.DefaultThresholds()).PlanMeasurement,
	); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("stale PostgreSQL credential tuple was not blocked: %v", err)
	}
	if err := values.pool.QueryRow(ctx, `SELECT last_seen_at FROM devices WHERE id=$1`, second.DeviceID).Scan(&lastSeenAt); err != nil {
		t.Fatal(err)
	}
	if lastSeenAt != nil {
		t.Fatalf("stale PostgreSQL credential tuple changed last_seen_at: %v", lastSeenAt)
	}
	if _, err := values.ProcessDeviceMeasurement(ctx, device, measurement, nil, func(alerts.State, domain.Measurement) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: domain.Alert{}}}
	}); err == nil {
		t.Fatal("invalid PostgreSQL ingest unexpectedly succeeded")
	}
	if err := values.pool.QueryRow(ctx, `SELECT last_seen_at FROM devices WHERE id=$1`, second.DeviceID).Scan(&lastSeenAt); err != nil {
		t.Fatal(err)
	}
	if lastSeenAt != nil {
		t.Fatalf("failed PostgreSQL ingest changed last_seen_at: %v", lastSeenAt)
	}
	if duplicate, err := values.ProcessDeviceMeasurement(ctx, device, measurement, nil, alerts.NewEngine(alerts.DefaultThresholds()).PlanMeasurement); err != nil || duplicate {
		t.Fatalf("successful PostgreSQL device ingest: duplicate=%v err=%v", duplicate, err)
	}
	if err := values.pool.QueryRow(ctx, `SELECT last_seen_at FROM devices WHERE id=$1`, second.DeviceID).Scan(&lastSeenAt); err != nil {
		t.Fatal(err)
	}
	if lastSeenAt == nil || !lastSeenAt.Equal(measurement.ReceivedAt) {
		t.Fatalf("successful PostgreSQL ingest did not update last_seen_at: %v", lastSeenAt)
	}
	if allowed, err := values.HouseholdCanAccessPatient(ctx, first.HouseholdID, second.PatientID); err != nil || allowed {
		t.Fatalf("first household accessed second patient: allowed=%v err=%v", allowed, err)
	}
	for identity, expectedRecipient := range map[*BootstrapIdentity]string{
		&first: "first-family-chat", &second: "second-family-chat",
	} {
		recipients, err := values.TelegramRecipients(ctx, identity.PatientID)
		if err != nil || len(recipients) != 1 || recipients[0] != expectedRecipient {
			t.Fatalf("patient %s recipients=%#v err=%v", identity.PatientID, recipients, err)
		}
	}
	session, err := values.ResolveActiveFamilySession(ctx, second.FamilyTokenHash, now)
	if err != nil || session.HouseholdID != second.HouseholdID {
		t.Fatalf("second family session resolved incorrectly: access=%#v err=%v", session, err)
	}
	if _, err := values.ResolveActiveFamilySession(ctx, second.FamilyTokenHash, expiresAt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired family session remained active: %v", err)
	}
	webCredential := FamilyWebSessionCredential{
		ID: testUUID(t), TokenHash: HashAccessToken("postgres-browser-session-0123456789abcdef"),
		CSRFTokenHash: HashAccessToken("postgres-csrf-token-0123456789abcdef"),
		ExpiresAt:     now.Add(30 * time.Second),
	}
	issuedWebSession, err := values.IssueFamilyWebSession(ctx, second.FamilyTokenHash, webCredential, now)
	if err != nil || issuedWebSession.HouseholdID != second.HouseholdID {
		t.Fatalf("issue PostgreSQL family web session: access=%#v err=%v", issuedWebSession, err)
	}
	resolvedWebSession, err := values.ResolveActiveFamilyWebSession(ctx, webCredential.TokenHash, now)
	if err != nil || resolvedWebSession.ID != webCredential.ID ||
		!bytes.Equal(resolvedWebSession.CSRFTokenHash, webCredential.CSRFTokenHash) {
		t.Fatalf("resolve PostgreSQL family web session: access=%#v err=%v", resolvedWebSession, err)
	}
	if _, err := values.ResolveActiveFamilyWebSession(ctx, second.FamilyTokenHash, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("provisioned family token authenticated as PostgreSQL web session: %v", err)
	}
	if _, err := values.ResolveActiveFamilyWebSession(ctx, webCredential.TokenHash, webCredential.ExpiresAt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired PostgreSQL family web session remained active: %v", err)
	}
	if err := values.RevokeDevice(ctx, second.DeviceID, now); err != nil {
		t.Fatal(err)
	}
	if _, err := values.ResolveActiveDevice(ctx, second.DeviceTokenHash, now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoked PostgreSQL device remained active: %v", err)
	}
	if err := values.ValidateProductionAccess(ctx, now); !errors.Is(err, ErrAccessNotProvisioned) {
		t.Fatalf("incomplete PostgreSQL access graph passed readiness: %v", err)
	}

	var storedDeviceHash, storedFamilyHash []byte
	if err := values.pool.QueryRow(ctx, `SELECT token_hash FROM devices WHERE id=$1`, first.DeviceID).Scan(&storedDeviceHash); err != nil {
		t.Fatal(err)
	}
	if err := values.pool.QueryRow(ctx, `SELECT token_hash FROM family_sessions WHERE id=$1`, first.FamilySessionID).Scan(&storedFamilyHash); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(storedDeviceHash, first.DeviceTokenHash) || !bytes.Equal(storedFamilyHash, first.FamilyTokenHash) {
		t.Fatal("PostgreSQL did not persist the expected one-way token digests")
	}
	if bytes.Contains(storedDeviceHash, []byte("first-device-token")) || bytes.Contains(storedFamilyHash, []byte("first-family-token")) {
		t.Fatal("PostgreSQL persisted a plaintext access token")
	}
	if err := values.BootstrapAccess(ctx, first); err != nil {
		t.Fatalf("exact PostgreSQL bootstrap retry failed: %v", err)
	}
	changed := first
	changed.DeviceName = "Другой телефон"
	if err := values.BootstrapAccess(ctx, changed); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("PostgreSQL bootstrap changed an insert-only device: %v", err)
	}
	changed = first
	changedExpiry := now.Add(time.Hour)
	changed.FamilySessionExpiresAt = &changedExpiry
	if err := values.BootstrapAccess(ctx, changed); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("PostgreSQL bootstrap changed an insert-only expiry: %v", err)
	}
	crossRole := BootstrapIdentity{
		HouseholdID: testUUID(t), PatientID: testUUID(t), DeviceID: testUUID(t),
		DeviceTokenHash:  first.FamilyTokenHash,
		BackendBindingID: "backend-binding-third", CredentialID: "credential-third", CredentialRevision: 1,
		FamilySessionID: testUUID(t), FamilyTokenHash: HashAccessToken("third-family-token-0123456789abcdef"),
	}
	if err := values.BootstrapAccess(ctx, crossRole); !errors.Is(err, ErrCredentialConflict) {
		t.Fatalf("PostgreSQL reused a family digest for a device: %v", err)
	}
	uppercase := BootstrapIdentity{
		HouseholdID: strings.ToUpper(testUUID(t)), PatientID: strings.ToUpper(testUUID(t)),
		DeviceID: strings.ToUpper(testUUID(t)), DeviceTokenHash: HashAccessToken("uppercase-device-token-0123456789abcdef"),
		BackendBindingID: "backend-binding-uppercase", CredentialID: "credential-uppercase", CredentialRevision: 1,
		FamilySessionID: strings.ToUpper(testUUID(t)), FamilyTokenHash: HashAccessToken("uppercase-family-token-0123456789abcdef"),
	}
	if err := values.BootstrapAccess(ctx, uppercase); err != nil {
		t.Fatalf("PostgreSQL uppercase provisioning input: %v", err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, strings.ToLower(uppercase.HouseholdID))
	}()
	uppercaseDevice, err := values.ResolveActiveDevice(ctx, uppercase.DeviceTokenHash, now)
	if err != nil {
		t.Fatal(err)
	}
	uppercaseSession, err := values.ResolveActiveFamilySession(ctx, uppercase.FamilyTokenHash, now)
	if err != nil {
		t.Fatal(err)
	}
	if uppercaseDevice.ID != strings.ToLower(uppercase.DeviceID) ||
		uppercaseDevice.PatientID != strings.ToLower(uppercase.PatientID) ||
		uppercaseSession.ID != strings.ToLower(uppercase.FamilySessionID) ||
		uppercaseSession.HouseholdID != strings.ToLower(uppercase.HouseholdID) {
		t.Fatalf("PostgreSQL UUID canonicalization mismatch: device=%#v session=%#v", uppercaseDevice, uppercaseSession)
	}
}

func TestPostgresProductionReadinessRequiresRecipientForEveryPatientHousehold(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	identity := BootstrapIdentity{
		HouseholdID: testUUID(t), PatientID: testUUID(t), DeviceID: testUUID(t),
		DeviceTokenHash:  HashAccessToken("recipient-readiness-device-token-0123456789"),
		BackendBindingID: "recipient-readiness-binding", CredentialID: "recipient-readiness-credential", CredentialRevision: 1,
		FamilySessionID: testUUID(t), FamilyTokenHash: HashAccessToken("recipient-readiness-family-token-0123456789"),
	}
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, identity.HouseholdID)
	}()
	now := time.Now().UTC()
	if err := values.ValidateProductionAccess(ctx, now); !errors.Is(err, ErrAccessNotProvisioned) {
		t.Fatalf("PostgreSQL patient without Telegram recipient passed readiness: %v", err)
	}
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO family_members (id,household_id,email,display_name,role,telegram_chat_id)
		VALUES ($1,$2,'recipient-readiness@example.invalid','Родственник','relative','123456789')`,
		testUUID(t), identity.HouseholdID,
	); err != nil {
		t.Fatal(err)
	}
	if err := values.ValidateProductionAccess(ctx, now); err != nil {
		t.Fatalf("complete PostgreSQL delivery graph failed readiness: %v", err)
	}
}

func TestPostgresDeviceActivationIsAtomicAndSingleUseUnderConcurrency(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	now := time.Now().UTC().Truncate(time.Millisecond)
	plan := testDeviceActivationProvisioning(now)
	plan.Identity.HouseholdID = testUUID(t)
	plan.Identity.PatientID = testUUID(t)
	plan.Identity.DeviceID = testUUID(t)
	plan.Identity.FamilySessionID = testUUID(t)
	plan.Identity.BackendBindingID = "activation-binding-" + plan.Identity.DeviceID
	plan.Identity.CredentialID = "activation-credential-" + plan.Identity.DeviceID
	plan.Identity.FamilyTokenHash = HashAccessToken("activation-family-" + plan.Identity.FamilySessionID)
	plan.Identity.TelegramRecipients = []string{"activation-chat-" + plan.Identity.HouseholdID}
	plan.Activation.ID = testUUID(t)
	plan.Activation.CodeHash = HashAccessToken("activation-code-" + plan.Activation.ID)
	plan.Activation.DeviceNonceHash = HashAccessToken("activation-nonce-" + plan.Identity.DeviceID)
	if err := values.ProvisionDeviceActivation(ctx, plan); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, plan.Identity.HouseholdID)
	}()
	if err := values.ValidateProductionAccess(ctx, now); err != nil {
		t.Fatalf("live pending PostgreSQL activation failed readiness: %v", err)
	}
	if _, err := values.ResolveActiveDevice(ctx, HashAccessToken("not-issued"), now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("pending PostgreSQL device authenticated before consume: %v", err)
	}
	wrong := DeviceActivationConsume{
		CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
		DeviceNonceHash: HashAccessToken("wrong-postgres-nonce"),
		DeviceTokenHash: HashAccessToken("wrong-postgres-token"), At: now.Add(time.Minute),
	}
	if _, err := values.ConsumeDeviceActivation(ctx, wrong); !errors.Is(err, ErrNotFound) {
		t.Fatalf("wrong PostgreSQL device proof did not fail closed: %v", err)
	}

	type activationResult struct {
		access    DeviceAccess
		tokenHash []byte
		err       error
	}
	start := make(chan struct{})
	results := make(chan activationResult, 2)
	var workers sync.WaitGroup
	for _, rawToken := range []string{"postgres-activation-token-a", "postgres-activation-token-b"} {
		workers.Add(1)
		go func(rawToken string) {
			defer workers.Done()
			<-start
			tokenHash := HashAccessToken(rawToken)
			access, consumeErr := values.ConsumeDeviceActivation(ctx, DeviceActivationConsume{
				CodeHash: plan.Activation.CodeHash, DeviceID: plan.Identity.DeviceID,
				DeviceNonceHash: plan.Activation.DeviceNonceHash,
				DeviceTokenHash: tokenHash, At: now.Add(2 * time.Minute),
			})
			results <- activationResult{access: access, tokenHash: tokenHash, err: consumeErr}
		}(rawToken)
	}
	close(start)
	workers.Wait()
	close(results)
	winners := 0
	var winningHash []byte
	for outcome := range results {
		if outcome.err == nil {
			winners++
			winningHash = outcome.tokenHash
			if outcome.access.ID != plan.Identity.DeviceID || outcome.access.PatientID != plan.Identity.PatientID {
				t.Fatalf("PostgreSQL activation returned wrong binding: %#v", outcome.access)
			}
		} else if !errors.Is(outcome.err, ErrNotFound) {
			t.Fatalf("losing PostgreSQL activation returned unexpected error: %v", outcome.err)
		}
	}
	if winners != 1 {
		t.Fatalf("concurrent PostgreSQL activation winners=%d want 1", winners)
	}
	resolved, err := values.ResolveActiveDevice(ctx, winningHash, now.Add(3*time.Minute))
	if err != nil || resolved.ID != plan.Identity.DeviceID {
		t.Fatalf("winning PostgreSQL device token was not durable: access=%#v err=%v", resolved, err)
	}
	var storedTokenHash, storedCodeHash, storedNonceHash []byte
	var consumedAt *time.Time
	if err := values.pool.QueryRow(ctx, `
		SELECT device.token_hash, activation.code_hash, activation.device_nonce_hash, activation.consumed_at
		FROM devices device
		JOIN device_activation_codes activation ON activation.device_id=device.id
		WHERE device.id=$1 AND activation.id=$2`, plan.Identity.DeviceID, plan.Activation.ID).Scan(
		&storedTokenHash, &storedCodeHash, &storedNonceHash, &consumedAt,
	); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(storedTokenHash, winningHash) || !bytes.Equal(storedCodeHash, plan.Activation.CodeHash) ||
		!bytes.Equal(storedNonceHash, plan.Activation.DeviceNonceHash) || consumedAt == nil {
		t.Fatalf("PostgreSQL activation persistence mismatch: token=%x code=%x nonce=%x consumed=%v",
			storedTokenHash, storedCodeHash, storedNonceHash, consumedAt)
	}
}

func TestPostgresIngestWaitsForPatientBeforeLockingDevice(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	values, err := NewPostgres(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer values.Close()
	if err := values.InitializeSchema(ctx); err != nil {
		t.Fatal(err)
	}

	identity := BootstrapIdentity{
		HouseholdID: testUUID(t), PatientID: testUUID(t), DeviceID: testUUID(t),
		DeviceTokenHash:  HashAccessToken("lock-order-device-token-0123456789abcdef"),
		BackendBindingID: "lock-order-binding", CredentialID: "lock-order-credential", CredentialRevision: 1,
		FamilySessionID: testUUID(t), FamilyTokenHash: HashAccessToken("lock-order-family-token-0123456789abcdef"),
	}
	if err := values.BootstrapAccess(ctx, identity); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, identity.HouseholdID)
	}()
	device, err := values.ResolveActiveDevice(ctx, identity.DeviceTokenHash, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}

	guard, err := values.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Rollback(context.Background())
	if _, err := guard.Exec(ctx, `SELECT id FROM patients WHERE id=$1 FOR UPDATE`, identity.PatientID); err != nil {
		t.Fatal(err)
	}

	ingestDone := make(chan error, 1)
	go func() {
		measurement := postgresMeasurement(
			identity.PatientID,
			fmt.Sprintf("%064x", 91_001),
			time.Now().UTC().Truncate(time.Millisecond),
			110,
			1,
		)
		_, processErr := values.ProcessDeviceMeasurement(
			ctx,
			device,
			measurement,
			nil,
			func(alerts.State, domain.Measurement) []alerts.Change { return nil },
		)
		ingestDone <- processErr
	}()

	deadline := time.Now().Add(5 * time.Second)
	for {
		var waiting bool
		err := values.pool.QueryRow(ctx, `
			SELECT EXISTS (
				SELECT 1 FROM pg_stat_activity
				WHERE pid <> pg_backend_pid()
				  AND wait_event_type='Lock'
				  AND query LIKE '%FROM patients%'
				  AND query LIKE '%FOR UPDATE%'
			)`).Scan(&waiting)
		if err != nil {
			t.Fatal(err)
		}
		if waiting {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("ingest did not wait on the patient lock")
		}
		time.Sleep(10 * time.Millisecond)
	}

	if _, err := guard.Exec(ctx, `SET LOCAL lock_timeout='250ms'`); err != nil {
		t.Fatal(err)
	}
	if _, err := guard.Exec(ctx, `SELECT id FROM devices WHERE id=$1 FOR UPDATE`, identity.DeviceID); err != nil {
		t.Fatalf("ingest locked device before patient, enabling provisioning deadlock: %v", err)
	}
	if err := guard.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-ingestDone:
		if err != nil {
			t.Fatalf("ingest failed after lock-order check: %v", err)
		}
	case <-ctx.Done():
		t.Fatalf("ingest did not finish after patient lock release: %v", ctx.Err())
	}
}

func postgresMeasurement(patientID, eventID string, at time.Time, glucose int, sequence uint64) domain.Measurement {
	return domain.Measurement{
		EventID: eventID, PatientID: patientID, SensorID: "sensor-atomic",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at, PhoneTime: at,
		ReceivedAt: at.Add(time.Minute), GlucoseMgDL: glucose,
		TrendMgDLPerMinute: -0.2, Quality: domain.QualityValid,
		Sequence: sequence,
	}
}

func activatePostgresMonitoring(
	t *testing.T,
	ctx context.Context,
	values *Postgres,
	patientID string,
	at time.Time,
	eventID string,
) {
	t.Helper()
	value := postgresMeasurement(patientID, eventID, at, 110, 0)
	value.SensorID = "activation-" + eventID
	value.ReceivedAt = at
	duplicate, err := values.ProcessMeasurement(ctx, value, nil, func(alerts.State, domain.Measurement) []alerts.Change { return nil })
	if err != nil || duplicate {
		t.Fatalf("activate monitoring: duplicate=%v err=%v", duplicate, err)
	}
}

func testUUID(t *testing.T) string {
	t.Helper()
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		t.Fatal(err)
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	encoded := make([]byte, 36)
	hex.Encode(encoded[0:8], value[0:4])
	encoded[8] = '-'
	hex.Encode(encoded[9:13], value[4:6])
	encoded[13] = '-'
	hex.Encode(encoded[14:18], value[6:8])
	encoded[18] = '-'
	hex.Encode(encoded[19:23], value[8:10])
	encoded[23] = '-'
	hex.Encode(encoded[24:36], value[10:16])
	return string(encoded)
}
