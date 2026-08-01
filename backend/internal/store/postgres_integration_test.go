package store

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
)

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
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, householdID)
	})
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'atomic-patient')`,
		patientID, householdID,
	); err != nil {
		t.Fatal(err)
	}

	base := time.Now().UTC().Truncate(time.Millisecond)
	if err := values.PrimePatient(ctx, patientID, base.Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
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
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, householdID)
	})
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'concurrent-patient')`,
		patientID, householdID,
	); err != nil {
		t.Fatal(err)
	}
	base := time.Now().UTC().Truncate(time.Millisecond).Add(-time.Minute)
	if err := values.PrimePatient(ctx, patientID, base); err != nil {
		t.Fatal(err)
	}
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
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = values.pool.Exec(cleanupCtx, `DELETE FROM households WHERE id=$1`, householdID)
	})
	if _, err := values.pool.Exec(ctx, `
		INSERT INTO patients (id,household_id,display_name) VALUES ($1,$2,'restart-patient')`,
		patientID, householdID,
	); err != nil {
		t.Fatal(err)
	}

	base := time.Now().UTC().Truncate(time.Millisecond)
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	if err := values.PrimePatient(ctx, patientID, base); err != nil {
		t.Fatal(err)
	}
	if err := values.ProcessStaleness(ctx, patientID, base.Add(11*time.Minute), []string{"family-chat"}, engine.PlanStaleness); err != nil {
		t.Fatal(err)
	}
	before, err := values.OpenAlerts(ctx, patientID)
	if err != nil || len(before) != 1 || before[0].Kind != domain.AlertSignalLoss {
		t.Fatalf("signal loss did not open: alerts=%#v err=%v", before, err)
	}

	// A later PrimePatient models a process restart. The original durable
	// baseline and the existing signal-loss alert must both survive it.
	if err := values.PrimePatient(ctx, patientID, base.Add(12*time.Minute)); err != nil {
		t.Fatal(err)
	}
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

func postgresMeasurement(patientID, eventID string, at time.Time, glucose int, sequence uint64) domain.Measurement {
	return domain.Measurement{
		EventID: eventID, PatientID: patientID, SensorID: "sensor-atomic",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at, PhoneTime: at,
		ReceivedAt: at.Add(time.Minute), GlucoseMgDL: glucose,
		TrendMgDLPerMinute: -0.2, Quality: domain.QualityValid,
		Sequence: sequence,
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
