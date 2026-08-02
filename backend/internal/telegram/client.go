package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"glucose-monitor/backend/internal/domain"
)

type Client struct {
	Token      string
	HTTPClient *http.Client
	BaseURL    string
}

const maxTelegramResponseBytes = 64 << 10

func (c Client) NotifyRecipient(
	ctx context.Context,
	alert domain.Alert,
	patientDisplayName string,
	chatID string,
) error {
	if c.Token == "" {
		return errors.New("telegram token is not configured")
	}
	client := c.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 8 * time.Second}
	}
	baseURL := strings.TrimRight(c.BaseURL, "/")
	if baseURL == "" {
		baseURL = "https://api.telegram.org"
	}
	payload := map[string]any{
		"chat_id":              chatID,
		"text":                 message(alert, patientDisplayName),
		"disable_notification": false,
	}
	body, _ := json.Marshal(payload)
	url := baseURL + "/bot" + c.Token + "/sendMessage"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return safeError(err, c.Token)
	}
	req.Header.Set("Content-Type", "application/json")
	response, err := client.Do(req)
	if err != nil {
		return safeError(fmt.Errorf("telegram request failed: %w", err), c.Token)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, maxTelegramResponseBytes+1))
	if err != nil {
		return safeError(fmt.Errorf("read telegram response: %w", err), c.Token)
	}
	if len(responseBody) > maxTelegramResponseBytes {
		return errors.New("telegram response is too large")
	}
	if response.StatusCode/100 != 2 {
		return safeError(fmt.Errorf("telegram returned %s", response.Status), c.Token)
	}
	var envelope struct {
		OK          bool   `json:"ok"`
		Description string `json:"description"`
		Result      *struct {
			MessageID int64 `json:"message_id"`
		} `json:"result"`
	}
	if err := json.Unmarshal(responseBody, &envelope); err != nil {
		return safeError(fmt.Errorf("invalid telegram response: %w", err), c.Token)
	}
	if !envelope.OK {
		detail := strings.TrimSpace(envelope.Description)
		if detail == "" {
			detail = "request was rejected"
		}
		return safeError(fmt.Errorf("telegram rejected request: %s", detail), c.Token)
	}
	if envelope.Result == nil || envelope.Result.MessageID <= 0 {
		return errors.New("telegram returned an invalid message result")
	}
	return nil
}

func safeError(err error, token string) error {
	if err == nil {
		return nil
	}
	message := err.Error()
	if token != "" {
		message = strings.ReplaceAll(message, token, "[REDACTED]")
	}
	return errors.New(message)
}

func message(alert domain.Alert, patientDisplayName string) string {
	name := map[domain.AlertKind]string{
		domain.AlertLow:        "Низкий уровень глюкозы",
		domain.AlertHigh:       "Высокий уровень глюкозы",
		domain.AlertRapidFall:  "Глюкоза быстро снижается",
		domain.AlertRapidRise:  "Глюкоза быстро повышается",
		domain.AlertSignalLoss: "Нет свежих данных от датчика",
	}[alert.Kind]
	patientDisplayName = domain.NormalizePatientDisplayName(patientDisplayName)
	if patientDisplayName == "" {
		patientDisplayName = "Пациент"
	}
	parts := []string{"⚠️ " + name, "Пациент: " + patientDisplayName}
	if alert.GlucoseMgDL != nil {
		parts = append(parts, fmt.Sprintf("Значение: %d мг/дл", *alert.GlucoseMgDL))
	}
	parts = append(parts, "Время (UTC): "+alert.OpenedAt.UTC().Format("15:04 02.01.2006"))
	return strings.Join(parts, "\n")
}
