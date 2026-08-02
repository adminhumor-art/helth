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

const familyWebOrigin = "https://family.example"

func TestFamilySessionExchangeIssuesBoundSecureCookieAndCSRFToken(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{
		FamilyWebOrigins: []string{familyWebOrigin},
		FamilySessionTTL: 30 * time.Minute,
	})

	request := httptest.NewRequest(http.MethodPost, "/v1/family/session", nil)
	request.Header.Set("Authorization", "Bearer "+familyToken)
	request.Header.Set("Origin", familyWebOrigin)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", response.Code, response.Body.String())
	}
	cookie := response.Result().Cookies()
	if len(cookie) != 1 {
		t.Fatalf("expected one session cookie, got %#v", cookie)
	}
	if cookie[0].Name != "family_session" || cookie[0].Value == "" || cookie[0].Value == familyToken {
		t.Fatalf("issuer leaked or omitted the browser session: %#v", cookie[0])
	}
	if !cookie[0].HttpOnly || !cookie[0].Secure || cookie[0].SameSite != http.SameSiteStrictMode ||
		cookie[0].Path != "/" || cookie[0].Domain != "" {
		t.Fatalf("unsafe family session cookie attributes: %#v", cookie[0])
	}
	if cookie[0].MaxAge <= 0 || cookie[0].Expires.IsZero() {
		t.Fatalf("family session cookie is not bounded: %#v", cookie[0])
	}
	var body struct {
		CSRFToken string    `json:"csrfToken"`
		ExpiresAt time.Time `json:"expiresAt"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.CSRFToken) < 32 || body.ExpiresAt.IsZero() {
		t.Fatalf("issuer did not return a bounded CSRF capability: %#v", body)
	}
	if strings.Contains(response.Body.String(), familyToken) || strings.Contains(response.Body.String(), cookie[0].Value) {
		t.Fatal("family access material or HttpOnly session leaked into the response body")
	}

	snapshot := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	snapshot.AddCookie(cookie[0])
	snapshotResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(snapshotResponse, snapshot)
	if snapshotResponse.Code != http.StatusOK {
		t.Fatalf("issued session could not read its family: %d: %s", snapshotResponse.Code, snapshotResponse.Body.String())
	}

	rawAccessCookie := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	rawAccessCookie.AddCookie(&http.Cookie{Name: "family_session", Value: familyToken})
	rawAccessResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(rawAccessResponse, rawAccessCookie)
	if rawAccessResponse.Code != http.StatusUnauthorized {
		t.Fatalf("provisioning access material was accepted as a browser cookie: %d", rawAccessResponse.Code)
	}
}

func TestFamilySessionExchangeRejectsWrongCredentialAndOriginWithoutCookie(t *testing.T) {
	server := newTestServer(t, store.NewMemory(), Config{FamilyWebOrigins: []string{familyWebOrigin}})
	for _, test := range []struct {
		name   string
		token  string
		origin string
	}{
		{name: "unknown family access", token: "unknown-family-secret", origin: familyWebOrigin},
		{name: "device credential", token: deviceToken, origin: familyWebOrigin},
		{name: "missing origin", token: familyToken},
		{name: "foreign origin", token: familyToken, origin: "https://attacker.example"},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/v1/family/session", nil)
			request.Header.Set("Authorization", "Bearer "+test.token)
			if test.origin != "" {
				request.Header.Set("Origin", test.origin)
			}
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusUnauthorized && response.Code != http.StatusForbidden {
				t.Fatalf("expected authentication/origin rejection, got %d: %s", response.Code, response.Body.String())
			}
			if len(response.Result().Cookies()) != 0 {
				t.Fatalf("rejected exchange issued a cookie: %#v", response.Result().Cookies())
			}
		})
	}
}

func TestFamilySessionTTLIsAlwaysPositiveAndBounded(t *testing.T) {
	for _, ttl := range []time.Duration{time.Nanosecond, 72 * time.Hour} {
		t.Run(ttl.String(), func(t *testing.T) {
			server := newTestServer(t, store.NewMemory(), Config{
				FamilyWebOrigins: []string{familyWebOrigin}, FamilySessionTTL: ttl,
			})
			cookie, _ := exchangeFamilySession(t, server.Handler(), familyToken, familyWebOrigin)
			if cookie.MaxAge < 60 || cookie.MaxAge > int((24*time.Hour)/time.Second) {
				t.Fatalf("unsafe family session Max-Age=%d for requested TTL %s", cookie.MaxAge, ttl)
			}
		})
	}
}

func TestFamilySessionIssuerNeverLogsOrPassesRawAccessMaterialToStore(t *testing.T) {
	var logs bytes.Buffer
	values := &familySessionIssueFailStore{Memory: store.NewMemory()}
	server := newTestServer(t, values, Config{
		FamilyWebOrigins: []string{familyWebOrigin},
		Logger:           slog.New(slog.NewJSONHandler(&logs, nil)),
	})
	values.fail = true
	request := httptest.NewRequest(http.MethodPost, "/v1/family/session", nil)
	request.Header.Set("Authorization", "Bearer "+familyToken)
	request.Header.Set("Origin", familyWebOrigin)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("injected issuer failure: expected 500, got %d", response.Code)
	}
	if strings.Contains(logs.String(), familyToken) || strings.Contains(response.Body.String(), familyToken) {
		t.Fatal("raw family access leaked through issuer failure handling")
	}
	if !bytes.Equal(values.receivedHash, store.HashAccessToken(familyToken)) {
		t.Fatal("HTTP issuer passed anything other than a one-way family access digest to Store")
	}
	if len(response.Result().Cookies()) != 0 {
		t.Fatal("failed session persistence still issued a browser cookie")
	}
}

func TestAcknowledgeRequiresSessionBoundCSRFAndExactOrigin(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{FamilyWebOrigins: []string{familyWebOrigin}})
	now := time.Now().UTC()
	activateHTTPMonitoring(t, values, patientID, now)
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000031", PatientID: patientID,
		Kind: domain.AlertLow, OpenedAt: now,
	}
	if err := values.ProcessStaleness(
		context.Background(), patientID, now, nil,
		func(alerts.State, string, time.Time) []alerts.Change {
			return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
		},
	); err != nil {
		t.Fatal(err)
	}
	cookie, csrf := exchangeFamilySession(t, server.Handler(), familyToken, familyWebOrigin)

	for _, test := range []struct {
		name       string
		origin     string
		csrf       string
		addOrigins []string
	}{
		{name: "missing origin", csrf: csrf},
		{name: "foreign origin", origin: "https://attacker.example", csrf: csrf},
		{name: "missing csrf", origin: familyWebOrigin},
		{name: "wrong csrf", origin: familyWebOrigin, csrf: "wrong-csrf-token"},
		{name: "duplicate origin", csrf: csrf, addOrigins: []string{familyWebOrigin, familyWebOrigin}},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/v1/alerts/"+alert.ID+"/acknowledge", nil)
			request.AddCookie(cookie)
			if test.origin != "" {
				request.Header.Set("Origin", test.origin)
			}
			for _, origin := range test.addOrigins {
				request.Header.Add("Origin", origin)
			}
			if test.csrf != "" {
				request.Header.Set("X-CSRF-Token", test.csrf)
			}
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusForbidden {
				t.Fatalf("expected 403, got %d: %s", response.Code, response.Body.String())
			}
		})
	}
	open, err := values.OpenAlerts(context.Background(), patientID)
	if err != nil || len(open) != 1 || open[0].AcknowledgedAt != nil {
		t.Fatalf("rejected CSRF attempt changed alert: alerts=%#v err=%v", open, err)
	}

	request := httptest.NewRequest(http.MethodPost, "/v1/alerts/"+alert.ID+"/acknowledge", nil)
	request.AddCookie(cookie)
	request.Header.Set("Origin", familyWebOrigin)
	request.Header.Set("X-CSRF-Token", csrf)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("valid session-bound mutation failed: %d: %s", response.Code, response.Body.String())
	}
}

func TestAcknowledgeRejectsCSRFTokenFromAnotherSession(t *testing.T) {
	values := store.NewMemory()
	server := newTestServer(t, values, Config{FamilyWebOrigins: []string{familyWebOrigin}})
	firstCookie, _ := exchangeFamilySession(t, server.Handler(), familyToken, familyWebOrigin)
	_, secondCSRF := exchangeFamilySession(t, server.Handler(), familyToken, familyWebOrigin)

	request := httptest.NewRequest(http.MethodPost, "/v1/alerts/00000000-0000-4000-8000-000000000031/acknowledge", nil)
	request.AddCookie(firstCookie)
	request.Header.Set("Origin", familyWebOrigin)
	request.Header.Set("X-CSRF-Token", secondCSRF)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("CSRF token was not bound to its issuing session: %d: %s", response.Code, response.Body.String())
	}
}

func TestFamilyEndpointsRejectDuplicateSessionCookies(t *testing.T) {
	server := newTestServer(t, store.NewMemory(), Config{FamilyWebOrigins: []string{familyWebOrigin}})
	cookie, _ := exchangeFamilySession(t, server.Handler(), familyToken, familyWebOrigin)
	request := httptest.NewRequest(http.MethodGet, "/v1/patients/"+patientID+"/snapshot", nil)
	request.AddCookie(cookie)
	request.AddCookie(&http.Cookie{Name: familySessionCookieName, Value: cookie.Value})
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("duplicate family session cookies were accepted: %d: %s", response.Code, response.Body.String())
	}
}

func exchangeFamilySession(t *testing.T, handler http.Handler, accessToken, origin string) (*http.Cookie, string) {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/v1/family/session", nil)
	request.Header.Set("Authorization", "Bearer "+accessToken)
	request.Header.Set("Origin", origin)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("session exchange failed: %d: %s", response.Code, response.Body.String())
	}
	cookies := response.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("session exchange returned %d cookies", len(cookies))
	}
	var body struct {
		CSRFToken string `json:"csrfToken"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	return cookies[0], body.CSRFToken
}

type familySessionIssueFailStore struct {
	*store.Memory
	fail         bool
	receivedHash []byte
}

func (s *familySessionIssueFailStore) IssueFamilyWebSession(
	ctx context.Context,
	familyAccessTokenHash []byte,
	credential store.FamilyWebSessionCredential,
	at time.Time,
) (store.FamilyWebSessionAccess, error) {
	if !s.fail {
		return s.Memory.IssueFamilyWebSession(ctx, familyAccessTokenHash, credential, at)
	}
	s.receivedHash = append([]byte(nil), familyAccessTokenHash...)
	return store.FamilyWebSessionAccess{}, errors.New("injected family session persistence failure")
}
