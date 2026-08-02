package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

const (
	deviceToken            = "device-secret"
	familyToken            = "family-secret"
	patientID              = "00000000-0000-4000-8000-000000000001"
	testDeviceID           = "00000000-0000-4000-8000-000000000201"
	backendBindingID       = "backend-binding-1"
	credentialID           = "credential-1"
	credentialRevision     = int64(1)
	secondBackendBindingID = "backend-binding-2"
	secondCredentialID     = "credential-2"
)

func TestDeviceTokenSelectsItsOwnPatientAndPayloadCannotOverrideIt(t *testing.T) {
	values := store.NewMemory()
	bootstrapAccess(t, values, accessFixture{
		householdID: "00000000-0000-4000-8000-000000000101",
		patientID:   patientID, deviceID: "00000000-0000-4000-8000-000000000201",
		deviceToken: deviceToken, familySessionID: "00000000-0000-4000-8000-000000000301",
		familyToken: familyToken,
	})
	secondPatientID := "00000000-0000-4000-8000-000000000002"
	secondToken := "second-device-secret"
	bootstrapAccess(t, values, accessFixture{
		householdID: "00000000-0000-4000-8000-000000000102",
		patientID:   secondPatientID, deviceID: "00000000-0000-4000-8000-000000000202",
		deviceToken: secondToken, familySessionID: "00000000-0000-4000-8000-000000000302",
		familyToken: "second-family-secret", backendBindingID: secondBackendBindingID,
		credentialID: secondCredentialID, credentialRevision: 2,
	})
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())

	response := postMeasurementWithIdentity(t, server.Handler(), value, secondToken, func(payload map[string]any) {
		payload["deviceId"] = "00000000-0000-4000-8000-000000000202"
		payload["backendBindingId"] = secondBackendBindingID
		payload["credentialId"] = secondCredentialID
		payload["credentialRevision"] = int64(2)
	})
	if response.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", response.Code, response.Body.String())
	}
	if _, err := values.Latest(context.Background(), patientID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("token wrote into configured/other patient: %v", err)
	}
	latest, err := values.Latest(context.Background(), secondPatientID)
	if err != nil {
		t.Fatal(err)
	}
	if latest.PatientID != secondPatientID {
		t.Fatalf("backend trusted a payload/config patient: %#v", latest)
	}
}

func TestUnknownRevokedAndFamilyTokensCannotIngest(t *testing.T) {
	values := store.NewMemory()
	fixture := accessFixture{
		householdID: "00000000-0000-4000-8000-000000000101",
		patientID:   patientID, deviceID: "00000000-0000-4000-8000-000000000201",
		deviceToken: deviceToken, familySessionID: "00000000-0000-4000-8000-000000000301",
		familyToken: familyToken,
	}
	bootstrapAccess(t, values, fixture)
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	value := validMeasurement(time.Now().UTC())

	for _, token := range []string{"unknown-device-secret", familyToken} {
		if response := postMeasurement(t, server.Handler(), value, token); response.Code != http.StatusUnauthorized {
			t.Fatalf("token %q: expected 401, got %d", token, response.Code)
		}
	}
	if err := values.RevokeDevice(context.Background(), fixture.deviceID, time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	if response := postMeasurement(t, server.Handler(), value, deviceToken); response.Code != http.StatusUnauthorized {
		t.Fatalf("revoked token: expected 401, got %d", response.Code)
	}
}

func TestFamilySessionCanOnlyReadPatientsInItsHousehold(t *testing.T) {
	values := store.NewMemory()
	bootstrapAccess(t, values, accessFixture{
		householdID: "00000000-0000-4000-8000-000000000101",
		patientID:   patientID, deviceID: "00000000-0000-4000-8000-000000000201",
		deviceToken: deviceToken, familySessionID: "00000000-0000-4000-8000-000000000301",
		familyToken: familyToken,
	})
	foreignPatientID := "00000000-0000-4000-8000-000000000002"
	bootstrapAccess(t, values, accessFixture{
		householdID: "00000000-0000-4000-8000-000000000102",
		patientID:   foreignPatientID, deviceID: "00000000-0000-4000-8000-000000000202",
		deviceToken: "foreign-device-secret", familySessionID: "00000000-0000-4000-8000-000000000302",
		familyToken: "foreign-family-secret", backendBindingID: secondBackendBindingID,
		credentialID: secondCredentialID, credentialRevision: 2,
	})
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))

	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+foreignPatientID+"/snapshot", nil)
	setFamilySessionCookie(request, familyToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusNotFound {
		t.Fatalf("foreign patient leaked: expected 404, got %d: %s", response.Code, response.Body.String())
	}

	request = httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	request.Header.Set("Authorization", "Bearer "+deviceToken)
	response = httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("device token opened family API: expected 401, got %d", response.Code)
	}
}

func TestExpiredFamilySessionIsUnauthorized(t *testing.T) {
	values := store.NewMemory()
	expiresAt := time.Now().UTC().Add(-time.Minute)
	bootstrapAccess(t, values, accessFixture{
		householdID: "00000000-0000-4000-8000-000000000101",
		patientID:   patientID, deviceID: "00000000-0000-4000-8000-000000000201",
		deviceToken: deviceToken, familySessionID: "00000000-0000-4000-8000-000000000301",
		familyToken: familyToken, familyExpiresAt: &expiresAt,
	})
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	setFamilySessionCookie(request, familyToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("expired family session: expected 401, got %d", response.Code)
	}
}

func TestPatientPathRejectsMalformedUUIDBeforeStoreLookup(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/not-a-uuid/snapshot", nil)
	setFamilySessionCookie(request, familyToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("malformed patient ID: expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestFamilyPatientPathsCanonicalizeUppercaseUUID(t *testing.T) {
	const canonicalPatientID = "abcdefab-cdef-4abc-8def-abcdefabcdef"
	values := store.NewMemory()
	bootstrapAccess(t, values, accessFixture{
		householdID:     "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
		patientID:       canonicalPatientID,
		deviceID:        "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
		deviceToken:     deviceToken,
		familySessionID: "cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa",
		familyToken:     familyToken,
	})
	base := time.Now().UTC().Truncate(time.Second)
	measurement := validMeasurement(base)
	measurement.PatientID = canonicalPatientID
	measurement.ReceivedAt = base
	if _, err := values.ProcessMeasurement(
		context.Background(), measurement, nil,
		func(alerts.State, domain.Measurement) []alerts.Change { return nil },
	); err != nil {
		t.Fatal(err)
	}
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	uppercasePatientID := strings.ToUpper(canonicalPatientID)

	snapshotRequest := httptest.NewRequest(http.MethodGet, "/v1/patients/"+uppercasePatientID+"/snapshot", nil)
	setFamilySessionCookie(snapshotRequest, familyToken)
	snapshotResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(snapshotResponse, snapshotRequest)
	if snapshotResponse.Code != http.StatusOK {
		t.Fatalf("uppercase patient snapshot: expected 200, got %d: %s", snapshotResponse.Code, snapshotResponse.Body.String())
	}
	var snapshot domain.PatientSnapshot
	if err := json.Unmarshal(snapshotResponse.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.PatientID != canonicalPatientID {
		t.Fatalf("snapshot returned non-canonical patient ID %q", snapshot.PatientID)
	}

	historyURL := "/v1/patients/" + uppercasePatientID + "/measurements?from=" +
		base.Add(-time.Minute).Format(time.RFC3339) + "&to=" + base.Add(time.Minute).Format(time.RFC3339)
	historyRequest := httptest.NewRequest(http.MethodGet, historyURL, nil)
	setFamilySessionCookie(historyRequest, familyToken)
	historyResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(historyResponse, historyRequest)
	if historyResponse.Code != http.StatusOK {
		t.Fatalf("uppercase patient history: expected 200, got %d: %s", historyResponse.Code, historyResponse.Body.String())
	}
}

func TestFamilyAlertPathCanonicalizesUppercaseUUID(t *testing.T) {
	const canonicalPatientID = "abcdefab-cdef-4abc-8def-abcdefabcdef"
	const canonicalAlertID = "fedcbafe-dcba-4fed-8cba-fedcbafedcba"
	values := store.NewMemory()
	bootstrapAccess(t, values, accessFixture{
		householdID:     "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
		patientID:       canonicalPatientID,
		deviceID:        "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
		deviceToken:     deviceToken,
		familySessionID: "cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa",
		familyToken:     familyToken,
	})
	now := time.Now().UTC()
	activateHTTPMonitoring(t, values, canonicalPatientID, now)
	alert := domain.Alert{ID: canonicalAlertID, PatientID: canonicalPatientID, Kind: domain.AlertLow, OpenedAt: now}
	if err := values.ProcessStaleness(
		context.Background(), canonicalPatientID, now, nil,
		func(alerts.State, string, time.Time) []alerts.Change {
			return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
		},
	); err != nil {
		t.Fatal(err)
	}
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	request := httptest.NewRequest(http.MethodPost, "/v1/alerts/"+strings.ToUpper(canonicalAlertID)+"/acknowledge", nil)
	setFamilySessionCookie(request, familyToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("uppercase alert acknowledge: expected 204, got %d: %s", response.Code, response.Body.String())
	}
	open, err := values.OpenAlerts(context.Background(), canonicalPatientID)
	if err != nil || len(open) != 1 || open[0].AcknowledgedAt == nil {
		t.Fatalf("canonical alert was not acknowledged: alerts=%#v err=%v", open, err)
	}
}

func TestDuplicateOrMalformedAuthorizationIsRejected(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
	value := validMeasurement(time.Now().UTC())
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	for _, configure := range []func(*http.Request){
		func(request *http.Request) { request.Header.Set("Authorization", "Basic "+deviceToken) },
		func(request *http.Request) {
			request.Header.Add("Authorization", "Bearer "+deviceToken)
			request.Header.Add("Authorization", "Bearer "+deviceToken)
		},
	} {
		request := httptest.NewRequest(http.MethodPost, "/v1/device/measurements", bytes.NewReader(body))
		configure(request)
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("malformed authorization: expected 401, got %d", response.Code)
		}
	}
}

func TestIngestRejectsPayloadPatientIDInsteadOfTrustingIt(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
	value := validMeasurement(time.Now().UTC())
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var payload map[string]any
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	payload["patientId"] = "00000000-0000-4000-8000-000000000099"
	body, err = json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/device/measurements", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+deviceToken)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("payload patientId: expected 400, got %d: %s", response.Code, response.Body.String())
	}
	if _, err := values.Latest(context.Background(), patientID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("payload patientId reached storage: %v", err)
	}
}

func TestAuthenticationFailureNeverLogsRawToken(t *testing.T) {
	var logs bytes.Buffer
	values := &authFailStore{Memory: store.NewMemory()}
	server := New(Config{Logger: slog.New(slog.NewJSONHandler(&logs, nil))}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	rawToken := "raw-device-token-that-must-never-be-logged"
	response := postMeasurement(t, server.Handler(), validMeasurement(time.Now().UTC()), rawToken)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("injected auth failure: expected 500, got %d", response.Code)
	}
	if strings.Contains(logs.String(), rawToken) || strings.Contains(response.Body.String(), rawToken) {
		t.Fatal("raw token leaked through authentication error handling")
	}
	if !bytes.Equal(values.receivedHash, store.HashAccessToken(rawToken)) {
		t.Fatal("HTTP layer passed anything other than a one-way token digest to the store")
	}
}

func TestIngestRequiresExactActiveDeviceCredentialTuple(t *testing.T) {
	mutations := []struct {
		name   string
		mutate func(map[string]any)
	}{
		{name: "deviceId", mutate: func(value map[string]any) { value["deviceId"] = "00000000-0000-4000-8000-000000000299" }},
		{name: "backendBindingId", mutate: func(value map[string]any) { value["backendBindingId"] = "backend-binding-other" }},
		{name: "credentialId", mutate: func(value map[string]any) { value["credentialId"] = "credential-other" }},
		{name: "credentialRevision", mutate: func(value map[string]any) { value["credentialRevision"] = float64(2) }},
		{name: "combined", mutate: func(value map[string]any) {
			value["backendBindingId"] = "backend-binding-other"
			value["credentialId"] = "credential-other"
			value["credentialRevision"] = float64(2)
		}},
	}
	for _, test := range mutations {
		t.Run(test.name, func(t *testing.T) {
			values := store.NewMemory()
			server := newTestServer(t, values, Config{})
			response := postMeasurementWithIdentity(
				t, server.Handler(), validMeasurement(time.Now().UTC()), deviceToken, test.mutate,
			)
			if response.Code != http.StatusConflict {
				t.Fatalf("expected 409, got %d: %s", response.Code, response.Body.String())
			}
			if _, err := values.Latest(context.Background(), patientID); !errors.Is(err, store.ErrNotFound) {
				t.Fatalf("binding conflict stored a measurement: %v", err)
			}
			open, err := values.OpenAlerts(context.Background(), patientID)
			if err != nil || len(open) != 0 {
				t.Fatalf("binding conflict changed alerts: alerts=%#v err=%v", open, err)
			}
		})
	}
}

func TestIngestCanonicalizesValidUppercaseDeviceUUIDBeforeExactBindingMatch(t *testing.T) {
	const lowercaseDeviceID = "abcdefab-cdef-4abc-8def-abcdefabcdef"
	values := store.NewMemory()
	bootstrapAccess(t, values, accessFixture{
		householdID:     "00000000-0000-4000-8000-000000000101",
		patientID:       patientID,
		deviceID:        lowercaseDeviceID,
		deviceToken:     deviceToken,
		familySessionID: "00000000-0000-4000-8000-000000000301",
		familyToken:     familyToken,
	})
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	response := postMeasurementWithIdentity(
		t,
		server.Handler(),
		validMeasurement(time.Now().UTC()),
		deviceToken,
		func(payload map[string]any) {
			payload["deviceId"] = strings.ToUpper(lowercaseDeviceID)
		},
	)
	if response.Code != http.StatusAccepted {
		t.Fatalf("canonical uppercase device UUID: expected 202, got %d: %s", response.Code, response.Body.String())
	}
	if _, err := values.Latest(context.Background(), patientID); err != nil {
		t.Fatalf("canonical uppercase device UUID did not store measurement: %v", err)
	}
}

func TestIngestCredentialRevisionMatchesOpenAPIJSONSafeIntegerBoundary(t *testing.T) {
	values := store.NewMemory()
	bootstrapAccess(t, values, accessFixture{
		householdID: "00000000-0000-4000-8000-000000000101",
		patientID:   patientID, deviceID: testDeviceID, deviceToken: deviceToken,
		familySessionID: "00000000-0000-4000-8000-000000000301", familyToken: familyToken,
		credentialRevision: store.MaxCredentialRevision,
	})
	server := New(Config{}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	accepted := postMeasurementWithIdentity(
		t, server.Handler(), validMeasurement(time.Now().UTC()), deviceToken,
		func(payload map[string]any) { payload["credentialRevision"] = store.MaxCredentialRevision },
	)
	if accepted.Code != http.StatusAccepted {
		t.Fatalf("maximum JSON-safe credential revision: expected 202, got %d: %s", accepted.Code, accepted.Body.String())
	}

	values = store.NewMemory()
	server = newTestServer(t, values, Config{})
	rejected := postMeasurementWithIdentity(
		t, server.Handler(), validMeasurement(time.Now().UTC()), deviceToken,
		func(payload map[string]any) { payload["credentialRevision"] = store.MaxCredentialRevision + 1 },
	)
	if rejected.Code != http.StatusBadRequest {
		t.Fatalf("credential revision outside JSON safe range: expected 400, got %d: %s", rejected.Code, rejected.Body.String())
	}
	if _, err := values.Latest(context.Background(), patientID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("out-of-range credential revision reached storage: %v", err)
	}
}

func TestMeasurementIngestIsIdempotent(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
	value := validMeasurement(time.Now().UTC())

	for attempt := 0; attempt < 2; attempt++ {
		response := postMeasurement(t, server.Handler(), value, deviceToken)
		if response.Code != http.StatusAccepted {
			t.Fatalf("attempt %d: expected 202, got %d: %s", attempt, response.Code, response.Body.String())
		}
		var result map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		if len(result) != 1 || result["accepted"] != true {
			t.Fatalf("attempt %d: response must be exact accepted=true, got %#v", attempt, result)
		}
	}
	stored, err := values.List(context.Background(), patientID, value.SensorTime.Add(-time.Minute), value.SensorTime.Add(time.Minute))
	if err != nil || len(stored) != 1 {
		t.Fatalf("exact retry changed storage cardinality: values=%#v err=%v", stored, err)
	}
}

func TestMeasurementIngestAcceptsAndroidDeterministicEventID(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
	value := validMeasurement(time.Now().UTC())
	value.EventID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMeasurementIngestRejectsConflictingEventID(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
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
	server := newTestServer(t, values, Config{})
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
	server := newTestServer(t, store.NewMemory(), Config{})
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", response.Code)
	}
}

func TestFamilyEndpointsRejectBearerSession(t *testing.T) {
	server := newTestServer(t, store.NewMemory(), Config{})
	now := time.Now().UTC()
	tests := []struct {
		method string
		path   string
	}{
		{method: http.MethodGet, path: "/v1/patients/" + patientID + "/snapshot"},
		{
			method: http.MethodGet,
			path: "/v1/patients/" + patientID + "/measurements?from=" +
				now.Add(-time.Hour).Format(time.RFC3339) + "&to=" + now.Format(time.RFC3339),
		},
		{method: http.MethodPost, path: "/v1/alerts/00000000-0000-4000-8000-000000000031/acknowledge"},
	}

	for _, test := range tests {
		for _, cookieToken := range []string{"", "invalid-family-cookie"} {
			name := "Bearer only"
			if cookieToken != "" {
				name = "invalid cookie with Bearer fallback"
			}
			t.Run(test.method+" "+test.path+"/"+name, func(t *testing.T) {
				request := httptest.NewRequest(test.method, test.path, nil)
				request.Header.Set("Authorization", "Bearer "+familyToken)
				if cookieToken != "" {
					setFamilySessionCookie(request, cookieToken)
				}
				response := httptest.NewRecorder()

				server.Handler().ServeHTTP(response, request)

				if response.Code != http.StatusUnauthorized {
					t.Fatalf("family Bearer token opened cookie-only endpoint: expected 401, got %d: %s", response.Code, response.Body.String())
				}
			})
		}
	}
}

func TestSnapshotReadsPersistedAcknowledgedAlertAfterServerRestart(t *testing.T) {
	values := store.NewMemory()
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	alert := domain.Alert{
		ID: "alert-persisted", PatientID: patientID, Kind: domain.AlertLow, OpenedAt: base,
	}
	activateHTTPMonitoring(t, values, alert.PatientID, base)
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, base, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	acknowledgedAt := base.Add(time.Minute)
	if err := values.AcknowledgeAlert(context.Background(), alert.PatientID, alert.ID, acknowledgedAt); err != nil {
		t.Fatal(err)
	}

	server := newTestServer(t, values, Config{})
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	setFamilySessionCookie(request, familyToken)
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
	server := newTestServer(t, values, Config{})
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	setFamilySessionCookie(request, familyToken)
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
			server := newTestServer(t, values, Config{})
			request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
			setFamilySessionCookie(request, familyToken)
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
	activateHTTPMonitoring(t, values, alert.PatientID, now)
	if err := values.ProcessStaleness(context.Background(), alert.PatientID, now, nil, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	server := newTestServer(t, values, Config{})

	for _, test := range []struct {
		alertID string
		status  int
	}{
		{alertID: "not-a-uuid", status: http.StatusBadRequest},
		{alertID: alert.ID, status: http.StatusNotFound},
	} {
		request := httptest.NewRequest(http.MethodPost, "/v1/alerts/"+test.alertID+"/acknowledge", nil)
		setFamilySessionCookie(request, familyToken)
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
	server := newTestServer(t, store.NewMemory(), Config{})
	value := validMeasurement(time.Now().UTC())
	value.GlucoseMgDL = 900
	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMeasurementIngestRequiresExplicitSequence(t *testing.T) {
	server := newTestServer(t, store.NewMemory(), Config{})
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
	server := newTestServer(t, store.NewMemory(), Config{})
	value := validMeasurement(time.Now().UTC())
	value.Sequence = 9_007_199_254_740_992

	response := postMeasurement(t, server.Handler(), value, deviceToken)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unsafe JSON sequence: expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestSimulatorMeasurementIsRejectedBeforeStorageAndAlerts(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{})
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
	server := newTestServer(t, store.NewMemory(), Config{})
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
	server := newTestServerWithRecipients(t, values, []string{"family-chat"}, engine)
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	activateHTTPMonitoring(t, values, patientID, base)

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

func TestOpenSignalLossRemainsOpenAfterRestartWithoutNewMeasurements(t *testing.T) {
	values := store.NewMemory()
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	first := newTestServerWithRecipients(t, values, []string{"family-chat"}, alerts.NewEngine(alerts.DefaultThresholds()))
	activateHTTPMonitoring(t, values, patientID, base)
	first.CheckStaleness(context.Background(), patientID, base.Add(11*time.Minute))
	before, err := values.OpenAlerts(context.Background(), patientID)
	if err != nil || len(before) != 1 || before[0].Kind != domain.AlertSignalLoss {
		t.Fatalf("signal loss was not opened: alerts=%#v err=%v", before, err)
	}

	restarted := newTestServerWithRecipients(t, values, []string{"family-chat"}, alerts.NewEngine(alerts.DefaultThresholds()))
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

type authFailStore struct {
	*store.Memory
	receivedHash []byte
}

func (s *authFailStore) ResolveActiveDevice(_ context.Context, tokenHash []byte, _ time.Time) (store.DeviceAccess, error) {
	s.receivedHash = append([]byte(nil), tokenHash...)
	return store.DeviceAccess{}, errors.New("injected authentication storage failure")
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

func (s *transactionFailStore) ProcessDeviceMeasurement(
	context.Context,
	store.DeviceAccess,
	domain.Measurement,
	[]string,
	store.MeasurementAlertPlanner,
) (bool, error) {
	return false, errors.New("injected atomic transaction failure")
}

func postMeasurement(t *testing.T, handler http.Handler, value domain.Measurement, token string) *httptest.ResponseRecorder {
	return postMeasurementWithIdentity(t, handler, value, token, nil)
}

func setFamilySessionCookie(request *http.Request, token string) {
	request.AddCookie(&http.Cookie{Name: "family_session", Value: token})
}

func postMeasurementWithIdentity(
	t *testing.T,
	handler http.Handler,
	value domain.Measurement,
	token string,
	mutate func(map[string]any),
) *httptest.ResponseRecorder {
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
	payload["deviceId"] = testDeviceID
	payload["backendBindingId"] = backendBindingID
	payload["credentialId"] = credentialID
	payload["credentialRevision"] = credentialRevision
	if mutate != nil {
		mutate(payload)
	}
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

type accessFixture struct {
	householdID        string
	patientID          string
	deviceID           string
	deviceToken        string
	familySessionID    string
	familyToken        string
	familyExpiresAt    *time.Time
	backendBindingID   string
	credentialID       string
	credentialRevision int64
}

func bootstrapAccess(t *testing.T, values *store.Memory, fixture accessFixture) {
	t.Helper()
	if fixture.backendBindingID == "" {
		fixture.backendBindingID = backendBindingID
	}
	if fixture.credentialID == "" {
		fixture.credentialID = credentialID
	}
	if fixture.credentialRevision == 0 {
		fixture.credentialRevision = credentialRevision
	}
	if err := values.BootstrapAccess(context.Background(), store.BootstrapIdentity{
		HouseholdID: fixture.householdID, PatientID: fixture.patientID,
		DeviceID: fixture.deviceID, DeviceTokenHash: store.HashAccessToken(fixture.deviceToken),
		BackendBindingID: fixture.backendBindingID, CredentialID: fixture.credentialID,
		CredentialRevision: fixture.credentialRevision,
		FamilySessionID:    fixture.familySessionID, FamilyTokenHash: store.HashAccessToken(fixture.familyToken),
		FamilySessionExpiresAt: fixture.familyExpiresAt,
	}); err != nil {
		t.Fatal(err)
	}
}

type accessBootstrapper interface {
	BootstrapAccess(context.Context, store.BootstrapIdentity) error
}

func newTestServer(t *testing.T, values store.Store, config Config) *Server {
	t.Helper()
	return newTestServerWithEngine(t, values, config, alerts.NewEngine(alerts.DefaultThresholds()))
}

func newTestServerWithEngine(t *testing.T, values store.Store, config Config, engine *alerts.Engine) *Server {
	return newTestServerSetup(t, values, config, engine, nil)
}

func newTestServerWithRecipients(t *testing.T, values store.Store, recipients []string, engine *alerts.Engine) *Server {
	return newTestServerSetup(t, values, Config{}, engine, recipients)
}

func newTestServerSetup(t *testing.T, values store.Store, config Config, engine *alerts.Engine, recipients []string) *Server {
	t.Helper()
	bootstrapper, ok := values.(accessBootstrapper)
	if !ok {
		t.Fatal("test store cannot bootstrap access")
	}
	if err := bootstrapper.BootstrapAccess(context.Background(), store.BootstrapIdentity{
		HouseholdID: "00000000-0000-4000-8000-000000000101",
		PatientID:   patientID, DeviceID: "00000000-0000-4000-8000-000000000201",
		DeviceTokenHash:    store.HashAccessToken(deviceToken),
		BackendBindingID:   backendBindingID,
		CredentialID:       credentialID,
		CredentialRevision: credentialRevision,
		FamilySessionID:    "00000000-0000-4000-8000-000000000301",
		FamilyTokenHash:    store.HashAccessToken(familyToken),
		TelegramRecipients: recipients,
	}); err != nil {
		t.Fatal(err)
	}
	return New(config, values, engine)
}

func activateHTTPMonitoring(t *testing.T, values *store.Memory, targetPatientID string, at time.Time) {
	t.Helper()
	_, err := values.ProcessMeasurement(context.Background(), domain.Measurement{
		EventID: "activation-" + targetPatientID, PatientID: targetPatientID, SensorID: "activation-sensor",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at, PhoneTime: at, ReceivedAt: at,
		GlucoseMgDL: 110, Quality: domain.QualityValid,
	}, nil, func(alerts.State, domain.Measurement) []alerts.Change { return nil })
	if err != nil {
		t.Fatal(err)
	}
}
