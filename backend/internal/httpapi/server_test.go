package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
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

func TestSnapshotRequiresFamilyAuthorization(t *testing.T) {
	server := New(Config{DeviceToken: deviceToken, FamilySessionToken: familyToken, PatientID: patientID}, store.NewMemory(), alerts.NewEngine(alerts.DefaultThresholds()))
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", response.Code)
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
	deliveries, err := values.DueAlertDeliveries(context.Background(), base.Add(11*time.Minute), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(deliveries) != 1 || deliveries[0].Alert.Kind != domain.AlertSignalLoss || deliveries[0].Recipient != "family-chat" {
		t.Fatalf("expected a queued signal-loss notification, got %#v", deliveries)
	}
	server.CheckStaleness(context.Background(), patientID, base.Add(12*time.Minute))
	deliveries, err = values.DueAlertDeliveries(context.Background(), base.Add(12*time.Minute), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(deliveries) != 1 {
		t.Fatalf("open signal-loss alert must be deduplicated, got %d deliveries", len(deliveries))
	}
}

func postMeasurement(t *testing.T, handler http.Handler, value domain.Measurement, token string) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(value)
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
