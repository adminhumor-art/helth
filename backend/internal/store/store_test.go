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
	if err := values.PrimePatient(context.Background(), "patient-1", base); err != nil {
		t.Fatal(err)
	}

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
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000020", PatientID: "patient-1",
		Kind: domain.AlertLow, OpenedAt: now,
	}
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, now, []string{"family-chat"}, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}

	first, err := values.ClaimDueAlertDeliveries(context.Background(), now, 10, "lease-one", now.Add(time.Minute))
	if err != nil || len(first) != 1 {
		t.Fatalf("first claim: deliveries=%#v err=%v", first, err)
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
