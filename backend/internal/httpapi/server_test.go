package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

const (
	deviceToken = "device-secret"
	familyToken = "family-secret"
	patientID   = "00000000-0000-4000-8000-000000000001"
)

func TestMeasurementIngestIsIdempotent(t *testing.T) {
	values := store.NewMemory()
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())

	for attempt := 0; attempt < 2; attempt++ {
		response := postMeasurement(t, server.Handler(), value, deviceToken)
		if response.Code != http.StatusAccepted {
			t.Fatalf("attempt %d: expected 202, got %d: %s", attempt, response.Code, response.Body.String())
		}
		var result struct {
			Duplicate bool `json:"duplicate"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		if result.Duplicate != (attempt == 1) {
			t.Fatalf("attempt %d: duplicate=%v", attempt, result.Duplicate)
		}
	}
}

func TestMeasurementIngestAcceptsAndroidDeterministicEventID(t *testing.T) {
	values := store.NewMemory()
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	value.EventID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMeasurementIngestRejectsConflictingEventID(t *testing.T) {
	values := store.NewMemory()
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	if response := postMeasurement(t, server.Handler(), value, deviceToken); response.Code != http.StatusAccepted {
		t.Fatalf("first ingest: expected 202, got %d: %s", response.Code, response.Body.String())
	}

	conflict := value
	conflict.GlucoseMgDL++
	response := postMeasurement(t, server.Handler(), conflict, deviceToken)
	if response.Code != http.StatusConflict {
		t.Fatalf("conflict: expected 409, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMeasurementAndAlertAreRejectedTogetherWhenAlertPersistenceFails(t *testing.T) {
	values := &transactionFailStore{Memory: store.NewMemory()}
	server := New(Config{
		DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID,
		TelegramRecipients: []string{"family-chat"},
	}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	value.GlucoseMgDL = 55

	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("expected atomic ingest failure, got %d: %s", response.Code, response.Body.String())
	}
	if _, err := values.Latest(context.Background(), patientID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("measurement must roll back with its alert, got %v", err)
	}
	open, err := values.OpenAlerts(context.Background(), patientID)
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 0 {
		t.Fatalf("failed transaction left open alerts: %#v", open)
	}
}

func TestSnapshotRequiresFamilyAuthorization(t *testing.T) {
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, store.NewMemory(), alerts.NewEngine(alerts.DefaultThresholds()))
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", response.Code)
	}
}

func TestSnapshotReadsPersistedAcknowledgedAlertAfterServerRestart(t *testing.T) {
	values := store.NewMemory()
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	alert := domain.Alert{
		ID: "alert-persisted", PatientID: patientID, Kind: domain.AlertLow, OpenedAt: base,
	}
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, base, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	acknowledgedAt := base.Add(time.Minute)
	if err := values.AcknowledgeAlert(context.Background(), alert.PatientID, alert.ID, acknowledgedAt); err != nil {
		t.Fatal(err)
	}

	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	if err := server.PrimePatient(context.Background(), patientID, base); err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	request.Header.Set("Authorization", "Bearer "+familyToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", response.Code, response.Body.String())
	}
	var snapshot domain.PatientSnapshot
	if err := json.Unmarshal(response.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if len(snapshot.OpenAlerts) != 1 || snapshot.OpenAlerts[0].ID != alert.ID || snapshot.OpenAlerts[0].AcknowledgedAt == nil {
		t.Fatalf("persisted acknowledged alert missing from snapshot: %#v", snapshot.OpenAlerts)
	}
}

func TestSnapshotUsesSingleStoreSnapshot(t *testing.T) {
	now := time.Now().UTC()
	values := &snapshotStore{
		Memory: store.NewMemory(),
		snapshot: domain.PatientSnapshot{
			PatientID: patientID,
			Latest:    ptrMeasurement(validStoredMeasurement(now, 55)),
			OpenAlerts: []domain.Alert{{
				ID: "00000000-0000-4000-8000-000000000030", PatientID: patientID,
				Kind: domain.AlertLow, OpenedAt: now,
			}},
		},
	}
	server := New(Config{FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	request.Header.Set("Authorization", "Bearer "+familyToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", response.Code, response.Body.String())
	}
	if values.snapshotCalls != 1 {
		t.Fatalf("combined snapshot calls=%d", values.snapshotCalls)
	}
}

func TestSnapshotFreshnessRequiresRecentSensorPhoneAndReceiptTimes(t *testing.T) {
	now := time.Now().UTC()
	for _, test := range []struct {
		name   string
		mutate func(*domain.Measurement)
	}{
		{
			name: "phone time is stale",
			mutate: func(value *domain.Measurement) {
				value.PhoneTime = now.Add(-11 * time.Minute)
			},
		},
		{
			name: "receipt time is stale",
			mutate: func(value *domain.Measurement) {
				value.ReceivedAt = now.Add(-11 * time.Minute)
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			latest := validStoredMeasurement(now.Add(-time.Minute), 110)
			latest.PhoneTime = now.Add(-time.Minute)
			latest.ReceivedAt = now.Add(-time.Minute)
			test.mutate(&latest)
			values := &snapshotStore{
				Memory: store.NewMemory(),
				snapshot: domain.PatientSnapshot{
					PatientID: patientID,
					Latest:    &latest,
				},
			}
			server := New(Config{FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))
			request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
			request.Header.Set("Authorization", "Bearer "+familyToken)
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusOK {
				t.Fatalf("expected 200, got %d: %s", response.Code, response.Body.String())
			}
			var snapshot domain.PatientSnapshot
			if err := json.Unmarshal(response.Body.Bytes(), &snapshot); err != nil {
				t.Fatal(err)
			}
			if snapshot.Freshness != domain.FreshnessStale {
				t.Fatalf("unsafe freshness=%q for %#v", snapshot.Freshness, latest)
			}
		})
	}
}

func TestAcknowledgeValidatesUUIDAndPatientScope(t *testing.T) {
	values := store.NewMemory()
	now := time.Now().UTC()
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000031", PatientID: "another-patient",
		Kind: domain.AlertLow, OpenedAt: now,
	}
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, now, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	server := New(Config{FamilySessionToken: familyToken, PatientID: patientID}, values, alerts.NewEngine(alerts.DefaultThresholds()))

	for _, test := range []struct {
		alertID string
		status  int
	}{
		{alertID: "not-a-uuid", status: http.StatusBadRequest},
		{alertID: alert.ID, status: http.StatusNotFound},
	} {
		request := httptest.NewRequest(http.MethodPost, "/v1/alerts/"+test.alertID+"/acknowledge", nil)
		request.Header.Set("Authorization", "Bearer "+familyToken)
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != test.status {
			t.Fatalf("alert %q: expected %d, got %d: %s", test.alertID, test.status, response.Code, response.Body.String())
		}
	}
	open, err := values.OpenAlerts(context.Background(), alert.PatientID)
	if err != nil || len(open) != 1 || open[0].AcknowledgedAt != nil {
		t.Fatalf("foreign alert was modified: alerts=%#v err=%v", open, err)
	}
}

func TestInvalidMeasurementIsRejected(t *testing.T) {
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, store.NewMemory(), alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	value.GlucoseMgDL = 900
	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMeasurementIngestRequiresExplicitSequence(t *testing.T) {
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, store.NewMemory(), alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var payload map[string]any
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	delete(payload, "sequence")
	body, err = json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/device/measurements", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+deviceToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("missing sequence: expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMeasurementIngestRejectsSequenceOutsideJSONSafeInteger(t *testing.T) {
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, store.NewMemory(), alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	value.Sequence = 9_007_199_254_740_992

	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unsafe JSON sequence: expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestSimulatorMeasurementIsRejectedBeforeStorageAndAlerts(t *testing.T) {
	values := store.NewMemory()
	server := New(Config{
		DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID,
		TelegramRecipients: []string{"family-chat"},
	}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	value.SensorFamily = domain.SensorSimulator

	response := postMeasurement(t, server.Handler(), value, deviceToken)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", response.Code, response.Body.String())
	}
	if _, err := values.Latest(context.Background(), patientID); err != store.ErrNotFound {
		t.Fatalf("simulator value must not be stored, got %v", err)
	}
}

func TestNonUUIDEventIDIsRejectedBeforeStorage(t *testing.T) {
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, store.NewMemory(), alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())
	value.EventID = "not-a-uuid"
	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestScheduledStalenessNotifiesWithoutNewMeasurement(t *testing.T) {
	values := store.NewMemory()
	engine := alerts.NewEngine(alerts.DefaultThresholds())
	server := New(Config{
		DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID,
		TelegramRecipients: []string{"family-chat"},
	}, values, engine)
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	if err := server.PrimePatient(context.Background(), patientID, base); err != nil {
		t.Fatal(err)
	}

	server.CheckStaleness(context.Background(), patientID, base.Add(11*time.Minute))
	deliveries, err := values.ClaimDueAlertDeliveries(
		context.Background(), base.Add(11*time.Minute), 10, "first-signal-lease", base.Add(12*time.Minute),
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(deliveries) != 1 || deliveries[0].Alert.Kind != domain.AlertSignalLoss || deliveries[0].Recipient != "family-chat" {
		t.Fatalf("expected a queued signal-loss notification, got %#v", deliveries)
	}
	if err := values.MarkAlertDeliveryFailed(
		context.Background(), deliveries[0].ID, "first-signal-lease",
		base.Add(11*time.Minute+30*time.Second), base.Add(12*time.Minute), "inspection",
	); err != nil {
		t.Fatal(err)
	}
	server.CheckStaleness(context.Background(), patientID, base.Add(12*time.Minute))
	deliveries, err = values.ClaimDueAlertDeliveries(
		context.Background(), base.Add(12*time.Minute), 10, "second-signal-lease", base.Add(13*time.Minute),
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(deliveries) != 1 {
		t.Fatalf("open signal-loss alert must be deduplicated, got %d deliveries", len(deliveries))
	}
}

func TestOpenSignalLossRemainsOpenAfterRestartWithoutMeasurements(t *testing.T) {
	values := store.NewMemory()
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	first := New(Config{
		DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID,
		TelegramRecipients: []string{"family-chat"},
	}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	if err := first.PrimePatient(context.Background(), patientID, base); err != nil {
		t.Fatal(err)
	}
	first.CheckStaleness(context.Background(), patientID, base.Add(11*time.Minute))
	before, err := values.OpenAlerts(context.Background(), patientID)
	if err != nil || len(before) != 1 || before[0].Kind != domain.AlertSignalLoss {
		t.Fatalf("signal loss was not opened: alerts=%#v err=%v", before, err)
	}

	restarted := New(Config{
		DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID,
		TelegramRecipients: []string{"family-chat"},
	}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	if err := restarted.PrimePatient(context.Background(), patientID, base.Add(12*time.Minute)); err != nil {
		t.Fatal(err)
	}
	restarted.CheckStaleness(context.Background(), patientID, base.Add(13*time.Minute))
	after, err := values.OpenAlerts(context.Background(), patientID)
	if err != nil {
		t.Fatal(err)
	}
	if len(after) != 1 || after[0].ID != before[0].ID || after[0].Kind != domain.AlertSignalLoss {
		t.Fatalf("restart closed or replaced active signal loss: before=%#v after=%#v", before, after)
	}
}

type transactionFailStore struct {
	*store.Memory
}

type snapshotStore struct {
	*store.Memory
	snapshot      domain.PatientSnapshot
	snapshotCalls int
}

func (s *snapshotStore) PatientSnapshot(context.Context, string) (domain.PatientSnapshot, error) {
	s.snapshotCalls++
	return s.snapshot, nil
}

func ptrMeasurement(value domain.Measurement) *domain.Measurement {
	return &value
}

func validStoredMeasurement(at time.Time, glucose int) domain.Measurement {
	value := validMeasurement(at)
	value.PatientID = patientID
	value.ReceivedAt = at
	value.GlucoseMgDL = glucose
	return value
}

func (s *transactionFailStore) ProcessMeasurement(
	context.Context,
	domain.Measurement,
	[]string,
	store.MeasurementAlertPlanner,
) (bool, error) {
	return false, errors.New("injected atomic transaction failure")
}

func postMeasurement(t *testing.T, handler http.Handler, value domain.Measurement, token string) *httptest.ResponseRecorder {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var payload map[string]any
	if err := json.Unmarshal(encoded, &payload); err != nil {
		t.Fatal(err)
	}
	delete(payload, "patientId")
	delete(payload, "receivedAt")
	body, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/device/measurements", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func validMeasurement(at time.Time) domain.Measurement {
	return domain.Measurement{
		EventID: "00000000-0000-4000-8000-000000000010", SensorID: "sensor-1",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at, PhoneTime: at,
		GlucoseMgDL: 110, TrendMgDLPerMinute: -0.2, Quality: domain.QualityValid,
	}
}
