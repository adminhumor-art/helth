package telegram

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"glucose-monitor/backend/internal/domain"
)

func TestNotifySendsOneMessagePerChat(t *testing.T) {
	t.Helper()
	type request struct {
		ChatID              string `json:"chat_id"`
		Text                string `json:"text"`
		DisableNotification bool   `json:"disable_notification"`
	}
	var received []request
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/botsecret/sendMessage" {
			t.Fatalf("unexpected path %q", r.URL.Path)
		}
		var value request
		if err := json.NewDecoder(r.Body).Decode(&value); err != nil {
			t.Fatal(err)
		}
		received = append(received, value)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"result":{"message_id":42}}`))
	}))
	defer server.Close()

	glucose := 58
	client := Client{
		Token: "secret", ChatIDs: []string{"family-1", "family-2"},
		HTTPClient: server.Client(), BaseURL: server.URL,
	}
	alert := domain.Alert{
		Kind: domain.AlertLow, OpenedAt: time.Date(2026, 7, 31, 3, 4, 0, 0, time.FixedZone("local", 3*60*60)),
		GlucoseMgDL: &glucose,
	}
	if err := client.Notify(context.Background(), alert); err != nil {
		t.Fatal(err)
	}
	if len(received) != 2 || received[0].ChatID != "family-1" || received[1].ChatID != "family-2" {
		t.Fatalf("unexpected recipients: %#v", received)
	}
	if !strings.Contains(received[0].Text, "58 мг/дл") || !strings.Contains(received[0].Text, "Время (UTC): 00:04") {
		t.Fatalf("unexpected message: %q", received[0].Text)
	}
	if received[0].DisableNotification {
		t.Fatal("critical alert must not disable Telegram notification")
	}
}

func TestNotifyReportsTelegramFailure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer server.Close()
	client := Client{Token: "secret", ChatIDs: []string{"family"}, HTTPClient: server.Client(), BaseURL: server.URL}
	if err := client.Notify(context.Background(), domain.Alert{Kind: domain.AlertSignalLoss, OpenedAt: time.Now()}); err == nil {
		t.Fatal("expected non-2xx response to be reported")
	}
}

func TestNotifyRejectsSuccessfulHTTPWithFailedTelegramEnvelope(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":false,"description":"chat not found"}`))
	}))
	defer server.Close()
	client := Client{Token: "secret", HTTPClient: server.Client(), BaseURL: server.URL}
	if err := client.NotifyRecipient(context.Background(), domain.Alert{Kind: domain.AlertLow, OpenedAt: time.Now()}, "family"); err == nil {
		t.Fatal("ok=false response was accepted")
	}
}

func TestNotifyRequiresValidTelegramMessageResult(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"result":{}}`))
	}))
	defer server.Close()
	client := Client{Token: "secret", HTTPClient: server.Client(), BaseURL: server.URL}
	if err := client.NotifyRecipient(context.Background(), domain.Alert{Kind: domain.AlertLow, OpenedAt: time.Now()}, "family"); err == nil {
		t.Fatal("Telegram response without message_id was accepted")
	}
}

func TestNotifyBoundsTelegramResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"result":{"message_id":42},"padding":"` + strings.Repeat("x", 70<<10) + `"}`))
	}))
	defer server.Close()
	client := Client{Token: "secret", HTTPClient: server.Client(), BaseURL: server.URL}
	if err := client.NotifyRecipient(context.Background(), domain.Alert{Kind: domain.AlertLow, OpenedAt: time.Now()}, "family"); err == nil {
		t.Fatal("oversized Telegram response was accepted")
	}
}

func TestNotifyNetworkErrorNeverContainsBotToken(t *testing.T) {
	const token = "very-secret-bot-token"
	client := Client{
		Token: token,
		HTTPClient: &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("dial failed")
		})},
	}
	err := client.NotifyRecipient(context.Background(), domain.Alert{Kind: domain.AlertLow, OpenedAt: time.Now()}, "family")
	if err == nil {
		t.Fatal("network error was ignored")
	}
	if strings.Contains(err.Error(), token) {
		t.Fatalf("bot token leaked through error: %q", err)
	}
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}
