package httpapi_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/delivery"
	"glucose-monitor/backend/internal/deviceprovisioning"
	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/httpapi"
	"glucose-monitor/backend/internal/store"
	"glucose-monitor/backend/internal/telegram"
)

func TestExistingAPIsCompleteActivationToFamilyAlertCycle(t *testing.T) {
	const (
		householdID      = "00000000-0000-4000-8000-000000000101"
		patientID        = "00000000-0000-4000-8000-000000000001"
		deviceID         = "00000000-0000-4000-8000-000000000201"
		familySessionID  = "00000000-0000-4000-8000-000000000301"
		activationID     = "00000000-0000-4000-8000-000000000401"
		backendBindingID = "backend-binding-1"
		credentialID     = "device-credential-1"
		familyOrigin     = "https://family.example"
		deviceAPIOrigin  = "https://api.family.example"
		telegramChatID   = "123456789"
	)

	activationCode := mustActivationCode(t)
	deviceNonce := mustOpaqueToken(t)
	familyAccess := mustOpaqueToken(t)
	provisionedAt := time.Now().UTC().Add(-time.Second)
	values := store.NewMemory()
	// The admin CLI ends at this Store boundary. Keeping that boundary direct
	// proves the product flow without inventing an admin HTTP endpoint.
	if err := values.ProvisionDeviceActivation(context.Background(), store.DeviceActivationProvisioning{
		Identity: store.BootstrapIdentity{
			HouseholdID: householdID, HouseholdName: "Семья",
			PatientID: patientID, PatientName: "Мама",
			DeviceID: deviceID, DeviceName: "Samsung",
			BackendBindingID: backendBindingID, CredentialID: credentialID, CredentialRevision: 1,
			FamilySessionID: familySessionID, FamilyTokenHash: store.HashAccessToken(familyAccess),
			TelegramRecipients: []string{telegramChatID},
		},
		Activation: store.DeviceActivationCredential{
			ID: activationID, CodeHash: store.HashAccessToken(activationCode),
			DeviceNonceHash: store.HashAccessToken(deviceNonce),
			CreatedAt:       provisionedAt, ExpiresAt: provisionedAt.Add(15 * time.Minute),
		},
	}); err != nil {
		t.Fatalf("admin provision activation: %v", err)
	}

	api := httpapi.New(httpapi.Config{
		DeviceAPIOrigin: deviceAPIOrigin, FamilyWebOrigins: []string{familyOrigin},
		FamilySessionTTL: 30 * time.Minute,
	}, values, alerts.NewEngine(alerts.DefaultThresholds()))
	apiServer := httptest.NewTLSServer(api.Handler())
	defer apiServer.Close()
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	client := apiServer.Client()
	client.Jar = jar

	provision := requestJSON(t, client, http.MethodPost, apiServer.URL+"/v1/device/provision", map[string]any{
		"activationCode": activationCode,
		"deviceId":       deviceID,
		"deviceNonce":    deviceNonce,
	}, nil)
	assertStatus(t, provision, http.StatusCreated, "device provision")
	var issued struct {
		DeviceToken        string `json:"deviceToken"`
		APIOrigin          string `json:"apiOrigin"`
		DeviceID           string `json:"deviceId"`
		PatientID          string `json:"patientId"`
		BackendBindingID   string `json:"backendBindingId"`
		CredentialID       string `json:"credentialId"`
		CredentialRevision int64  `json:"credentialRevision"`
	}
	decodeResult(t, provision, &issued)
	if !deviceprovisioning.ValidDeviceNonce(issued.DeviceToken) || issued.APIOrigin != deviceAPIOrigin ||
		issued.DeviceID != deviceID || issued.PatientID != patientID ||
		issued.BackendBindingID != backendBindingID || issued.CredentialID != credentialID ||
		issued.CredentialRevision != 1 {
		t.Fatalf("issued device credential does not match admin provisioning: %#v", issued)
	}

	measurementAt := time.Now().UTC().Truncate(time.Second).Add(-time.Second)
	measurementID := "00000000-0000-4000-8000-000000000010"
	ingest := requestJSON(t, client, http.MethodPost, apiServer.URL+"/v1/device/measurements", map[string]any{
		"deviceId": deviceID, "backendBindingId": backendBindingID,
		"credentialId": credentialID, "credentialRevision": 1,
		"eventId": measurementID, "sensorId": "gs1-real-device-slot",
		"sensorFamily": domain.SensorSibionicsGS1,
		"sensorTime":   measurementAt, "phoneTime": measurementAt,
		"glucoseMgDl": 55, "trendMgDlPerMinute": 0.0,
		"quality": domain.QualityValid, "sequence": 1,
	}, map[string]string{"Authorization": "Bearer " + issued.DeviceToken})
	assertStatus(t, ingest, http.StatusAccepted, "measurement ingest")

	telegramProbe := newTelegramProbe()
	telegramAPI := httptest.NewServer(telegramProbe)
	defer telegramAPI.Close()
	worker := delivery.NewWorker(values, telegram.Client{
		Token: "test-bot-token", HTTPClient: telegramAPI.Client(), BaseURL: telegramAPI.URL,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if telegramProbe.callCount() != 0 {
		t.Fatal("measurement ingest bypassed the durable delivery queue")
	}
	worker.RunOnce(context.Background(), time.Now().UTC().Add(time.Second))
	telegramRequest := telegramProbe.onlyRequest(t)
	if telegramRequest.Path != "/bottest-bot-token/sendMessage" ||
		telegramRequest.ChatID != telegramChatID ||
		!strings.Contains(telegramRequest.Text, "Мама") ||
		!strings.Contains(telegramRequest.Text, "Низкий уровень глюкозы") ||
		!strings.Contains(telegramRequest.Text, "55") || telegramRequest.DisableNotification {
		t.Fatalf("queued Telegram alert was sent with wrong content: %#v", telegramRequest)
	}
	worker.RunOnce(context.Background(), time.Now().UTC().Add(time.Minute))
	if telegramProbe.callCount() != 1 {
		t.Fatalf("sent delivery remained in the queue: Telegram calls=%d", telegramProbe.callCount())
	}

	familySession := requestJSON(t, client, http.MethodPost, apiServer.URL+"/v1/family/session", nil, map[string]string{
		"Authorization": "Bearer " + familyAccess,
		"Origin":        familyOrigin,
	})
	assertStatus(t, familySession, http.StatusCreated, "family session exchange")
	var familySessionBody struct {
		CSRFToken string `json:"csrfToken"`
	}
	decodeResult(t, familySession, &familySessionBody)
	if !deviceprovisioning.ValidDeviceNonce(familySessionBody.CSRFToken) {
		t.Fatalf("family session returned malformed CSRF token: %q", familySessionBody.CSRFToken)
	}

	snapshot := requestJSON(t, client, http.MethodGet, apiServer.URL+"/v1/patients/"+patientID+"/snapshot", nil, nil)
	assertStatus(t, snapshot, http.StatusOK, "family snapshot")
	var patientSnapshot domain.PatientSnapshot
	decodeResult(t, snapshot, &patientSnapshot)
	if patientSnapshot.PatientID != patientID || patientSnapshot.Freshness != domain.FreshnessFresh ||
		patientSnapshot.Latest == nil || patientSnapshot.Latest.EventID != measurementID ||
		patientSnapshot.Latest.GlucoseMgDL != 55 || len(patientSnapshot.OpenAlerts) != 1 ||
		patientSnapshot.OpenAlerts[0].Kind != domain.AlertLow {
		t.Fatalf("snapshot does not contain the accepted critical measurement and alert: %#v", patientSnapshot)
	}
	alertID := patientSnapshot.OpenAlerts[0].ID

	historyQuery := url.Values{
		"from": {measurementAt.Add(-time.Minute).Format(time.RFC3339)},
		"to":   {measurementAt.Add(time.Minute).Format(time.RFC3339)},
	}
	history := requestJSON(t, client, http.MethodGet,
		apiServer.URL+"/v1/patients/"+patientID+"/measurements?"+historyQuery.Encode(), nil, nil)
	assertStatus(t, history, http.StatusOK, "family history")
	var measurements []domain.Measurement
	decodeResult(t, history, &measurements)
	if len(measurements) != 1 || measurements[0].EventID != measurementID ||
		measurements[0].PatientID != patientID || measurements[0].GlucoseMgDL != 55 {
		t.Fatalf("history does not contain the accepted measurement: %#v", measurements)
	}

	acknowledge := requestJSON(t, client, http.MethodPost,
		apiServer.URL+"/v1/alerts/"+alertID+"/acknowledge", nil, map[string]string{
			"Origin": familyOrigin, "X-CSRF-Token": familySessionBody.CSRFToken,
		})
	assertStatus(t, acknowledge, http.StatusNoContent, "alert acknowledge")

	afterAcknowledge := requestJSON(t, client, http.MethodGet,
		apiServer.URL+"/v1/patients/"+patientID+"/snapshot", nil, nil)
	assertStatus(t, afterAcknowledge, http.StatusOK, "snapshot after acknowledge")
	decodeResult(t, afterAcknowledge, &patientSnapshot)
	if len(patientSnapshot.OpenAlerts) != 1 || patientSnapshot.OpenAlerts[0].ID != alertID ||
		patientSnapshot.OpenAlerts[0].AcknowledgedAt == nil {
		t.Fatalf("acknowledgement was not visible through the family API: %#v", patientSnapshot.OpenAlerts)
	}
}

type testHTTPResult struct {
	Status int
	Body   []byte
}

func requestJSON(
	t *testing.T,
	client *http.Client,
	method string,
	target string,
	payload any,
	headers map[string]string,
) testHTTPResult {
	t.Helper()
	var body io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			t.Fatal(err)
		}
		body = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, target, body)
	if err != nil {
		t.Fatal(err)
	}
	if payload != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		t.Fatal(err)
	}
	return testHTTPResult{Status: response.StatusCode, Body: responseBody}
}

func assertStatus(t *testing.T, result testHTTPResult, want int, step string) {
	t.Helper()
	if result.Status != want {
		t.Fatalf("%s: status=%d want=%d body=%s", step, result.Status, want, result.Body)
	}
}

func decodeResult(t *testing.T, result testHTTPResult, destination any) {
	t.Helper()
	if err := json.Unmarshal(result.Body, destination); err != nil {
		t.Fatalf("decode response status=%d body=%s: %v", result.Status, result.Body, err)
	}
}

func mustActivationCode(t *testing.T) string {
	t.Helper()
	value, err := deviceprovisioning.GenerateActivationCode(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func mustOpaqueToken(t *testing.T) string {
	t.Helper()
	value, err := deviceprovisioning.GenerateOpaqueToken(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return value
}

type capturedTelegramRequest struct {
	Path                string
	ChatID              string
	Text                string
	DisableNotification bool
}

type telegramProbe struct {
	mu       sync.Mutex
	requests []capturedTelegramRequest
}

func newTelegramProbe() *telegramProbe {
	return &telegramProbe{}
}

func (p *telegramProbe) ServeHTTP(w http.ResponseWriter, request *http.Request) {
	var payload struct {
		ChatID              string `json:"chat_id"`
		Text                string `json:"text"`
		DisableNotification bool   `json:"disable_notification"`
	}
	err := json.NewDecoder(request.Body).Decode(&payload)
	p.mu.Lock()
	p.requests = append(p.requests, capturedTelegramRequest{
		Path: request.URL.Path, ChatID: payload.ChatID, Text: payload.Text,
		DisableNotification: payload.DisableNotification,
	})
	p.mu.Unlock()
	if err != nil || request.Method != http.MethodPost {
		http.Error(w, "bad Telegram request", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, `{"ok":true,"result":{"message_id":1}}`)
}

func (p *telegramProbe) callCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.requests)
}

func (p *telegramProbe) onlyRequest(t *testing.T) capturedTelegramRequest {
	t.Helper()
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.requests) != 1 {
		t.Fatalf("Telegram requests=%d want=1", len(p.requests))
	}
	return p.requests[0]
}
