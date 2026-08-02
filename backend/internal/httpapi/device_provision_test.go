package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/deviceprovisioning"
	"glucose-monitor/backend/internal/store"
)

const deviceAPIOrigin = "https://api.family.example"

type deviceProvisionFixture struct {
	values         *store.Memory
	handler        http.Handler
	plan           store.DeviceActivationProvisioning
	activationCode string
	deviceNonce    string
}

func TestDeviceProvisionReturnsCredentialOnceAndCredentialCanIngest(t *testing.T) {
	fixture := newDeviceProvisionFixture(t, Config{DeviceAPIOrigin: deviceAPIOrigin})
	response := provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
	if response.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", response.Code, response.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	expectedKeys := []string{
		"apiOrigin", "backendBindingId", "credentialId", "credentialRevision",
		"deviceId", "deviceToken", "patientId",
	}
	if len(body) != len(expectedKeys) {
		t.Fatalf("device provisioning returned unexpected fields: %#v", body)
	}
	for _, key := range expectedKeys {
		if _, exists := body[key]; !exists {
			t.Fatalf("device provisioning omitted %s: %#v", key, body)
		}
	}
	deviceToken, _ := body["deviceToken"].(string)
	if !deviceprovisioning.ValidDeviceNonce(deviceToken) {
		t.Fatalf("issued device bearer is not a canonical 256-bit token: %q", deviceToken)
	}
	if body["apiOrigin"] != deviceAPIOrigin || body["deviceId"] != fixture.plan.Identity.DeviceID ||
		body["patientId"] != fixture.plan.Identity.PatientID ||
		body["backendBindingId"] != fixture.plan.Identity.BackendBindingID ||
		body["credentialId"] != fixture.plan.Identity.CredentialID ||
		body["credentialRevision"] != float64(fixture.plan.Identity.CredentialRevision) {
		t.Fatalf("issued credential metadata does not match admin provisioning: %#v", body)
	}
	for _, forbidden := range []string{fixture.activationCode, fixture.deviceNonce, "family-access-token"} {
		if strings.Contains(response.Body.String(), forbidden) {
			t.Fatalf("provisioning response leaked forbidden access material %q", forbidden)
		}
	}
	if len(response.Result().Cookies()) != 0 {
		t.Fatalf("device provisioning unexpectedly issued browser cookies: %#v", response.Result().Cookies())
	}

	measurement := validMeasurement(time.Now().UTC())
	ingest := postMeasurementWithIdentity(t, fixture.handler, measurement, deviceToken, func(payload map[string]any) {
		payload["deviceId"] = fixture.plan.Identity.DeviceID
		payload["backendBindingId"] = fixture.plan.Identity.BackendBindingID
		payload["credentialId"] = fixture.plan.Identity.CredentialID
		payload["credentialRevision"] = fixture.plan.Identity.CredentialRevision
	})
	if ingest.Code != http.StatusAccepted {
		t.Fatalf("freshly provisioned credential could not ingest: %d: %s", ingest.Code, ingest.Body.String())
	}

	repeat := provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
	if repeat.Code != http.StatusUnauthorized || strings.Contains(repeat.Body.String(), "deviceToken") {
		t.Fatalf("one-time activation was reusable: %d: %s", repeat.Code, repeat.Body.String())
	}
}

func TestDeviceProvisionRejectsWrongProofWithoutBurningActivation(t *testing.T) {
	for _, mutate := range []func(*map[string]any){
		func(body *map[string]any) {
			(*body)["activationCode"] = differentActivationCode((*body)["activationCode"].(string))
		},
		func(body *map[string]any) { (*body)["deviceId"] = "00000000-0000-4000-8000-000000000299" },
		func(body *map[string]any) {
			(*body)["deviceNonce"] = base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{9}, 32))
		},
	} {
		fixture := newDeviceProvisionFixture(t, Config{DeviceAPIOrigin: deviceAPIOrigin})
		body := validDeviceProvisionBody(fixture)
		mutate(&body)
		response := postDeviceProvisionJSON(t, fixture.handler, body)
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("wrong device proof: expected 401, got %d: %s", response.Code, response.Body.String())
		}
		correct := provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
		if correct.Code != http.StatusCreated {
			t.Fatalf("wrong proof burned activation: %d: %s", correct.Code, correct.Body.String())
		}
	}
}

func differentActivationCode(value string) string {
	changed := []byte(value)
	if changed[len(changed)-1] == '0' {
		changed[len(changed)-1] = '1'
	} else {
		changed[len(changed)-1] = '0'
	}
	return string(changed)
}

func TestDeviceProvisionRejectsMalformedOrNonExactJSONBeforeConsume(t *testing.T) {
	fixture := newDeviceProvisionFixture(t, Config{DeviceAPIOrigin: deviceAPIOrigin})
	for _, body := range []string{
		`{}`,
		`{"activationCode":"short","deviceId":"bad","deviceNonce":"bad"}`,
		`{"activationCode":"` + fixture.activationCode + ` ","deviceId":"` + fixture.plan.Identity.DeviceID + `","deviceNonce":"` + fixture.deviceNonce + `"}`,
		`{"activationCode":"` + fixture.activationCode + `","deviceId":"` + fixture.plan.Identity.DeviceID + `","deviceNonce":"` + fixture.deviceNonce + `","unknown":true}`,
		`{"activationCode":"` + fixture.activationCode + `","deviceId":"` + fixture.plan.Identity.DeviceID + `","deviceNonce":"` + fixture.deviceNonce + `"} {}`,
	} {
		request := httptest.NewRequest(http.MethodPost, "/v1/device/provision", strings.NewReader(body))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		fixture.handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("malformed provisioning body: expected 400, got %d: %s", response.Code, response.Body.String())
		}
	}
	correct := provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
	if correct.Code != http.StatusCreated {
		t.Fatalf("malformed requests consumed activation: %d: %s", correct.Code, correct.Body.String())
	}
}

func TestConcurrentHTTPDeviceProvisionHasExactlyOneCredentialResponse(t *testing.T) {
	fixture := newDeviceProvisionFixture(t, Config{DeviceAPIOrigin: deviceAPIOrigin})
	start := make(chan struct{})
	responses := make(chan *httptest.ResponseRecorder, 2)
	var workers sync.WaitGroup
	for range 2 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			responses <- provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
		}()
	}
	close(start)
	workers.Wait()
	close(responses)
	created, unauthorized := 0, 0
	for response := range responses {
		switch response.Code {
		case http.StatusCreated:
			created++
		case http.StatusUnauthorized:
			unauthorized++
		default:
			t.Fatalf("unexpected concurrent provisioning response %d: %s", response.Code, response.Body.String())
		}
	}
	if created != 1 || unauthorized != 1 {
		t.Fatalf("concurrent HTTP provisioning created=%d unauthorized=%d", created, unauthorized)
	}
}

func TestDeviceProvisionRandomFailureDoesNotConsumeActivation(t *testing.T) {
	fixture := newDeviceProvisionFixture(t, Config{
		DeviceAPIOrigin: deviceAPIOrigin,
		Random:          io.LimitReader(strings.NewReader("short"), 5),
	})
	response := provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
	if response.Code != http.StatusInternalServerError || strings.Contains(response.Body.String(), "deviceToken") {
		t.Fatalf("randomness failure did not fail closed: %d: %s", response.Code, response.Body.String())
	}

	fixture.handler = New(Config{DeviceAPIOrigin: deviceAPIOrigin}, fixture.values, alerts.NewEngine(alerts.DefaultThresholds())).Handler()
	retry := provisionDevice(t, fixture, fixture.activationCode, fixture.plan.Identity.DeviceID, fixture.deviceNonce)
	if retry.Code != http.StatusCreated {
		t.Fatalf("randomness failure consumed activation: %d: %s", retry.Code, retry.Body.String())
	}
}

func newDeviceProvisionFixture(t *testing.T, config Config) deviceProvisionFixture {
	t.Helper()
	values := store.NewMemory()
	activationCode, err := deviceprovisioning.GenerateActivationCode(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	deviceNonce, err := deviceprovisioning.GenerateOpaqueToken(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	plan := store.DeviceActivationProvisioning{
		Identity: store.BootstrapIdentity{
			HouseholdID: "00000000-0000-4000-8000-000000000101", HouseholdName: "Семья",
			PatientID: patientID, PatientName: "Мама",
			DeviceID: testDeviceID, DeviceName: "Samsung",
			BackendBindingID: backendBindingID, CredentialID: credentialID, CredentialRevision: credentialRevision,
			FamilySessionID:    "00000000-0000-4000-8000-000000000301",
			FamilyTokenHash:    store.HashAccessToken("family-access-token"),
			TelegramRecipients: []string{"123456789"},
		},
		Activation: store.DeviceActivationCredential{
			ID:       "00000000-0000-4000-8000-000000000401",
			CodeHash: store.HashAccessToken(activationCode), DeviceNonceHash: store.HashAccessToken(deviceNonce),
			CreatedAt: now, ExpiresAt: now.Add(15 * time.Minute),
		},
	}
	if err := values.ProvisionDeviceActivation(context.Background(), plan); err != nil {
		t.Fatal(err)
	}
	return deviceProvisionFixture{
		values: values, handler: New(config, values, alerts.NewEngine(alerts.DefaultThresholds())).Handler(),
		plan: plan, activationCode: activationCode, deviceNonce: deviceNonce,
	}
}

func validDeviceProvisionBody(fixture deviceProvisionFixture) map[string]any {
	return map[string]any{
		"activationCode": fixture.activationCode,
		"deviceId":       fixture.plan.Identity.DeviceID,
		"deviceNonce":    fixture.deviceNonce,
	}
}

func provisionDevice(
	t *testing.T,
	fixture deviceProvisionFixture,
	activationCode string,
	deviceID string,
	deviceNonce string,
) *httptest.ResponseRecorder {
	t.Helper()
	return postDeviceProvisionJSON(t, fixture.handler, map[string]any{
		"activationCode": activationCode, "deviceId": deviceID, "deviceNonce": deviceNonce,
	})
}

func postDeviceProvisionJSON(t *testing.T, handler http.Handler, body map[string]any) *httptest.ResponseRecorder {
	t.Helper()
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/device/provision", bytes.NewReader(encoded))
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}
