package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"glucose-monitor/backend/internal/domain"
)

type Client struct {
	Token      string
	ChatIDs    []string
	HTTPClient *http.Client
	BaseURL    string
}

func (c Client) Notify(ctx context.Context, alert domain.Alert) error {
	if c.Token == "" || len(c.ChatIDs) == 0 {
		return nil
	}
	var errs []error
	for _, chatID := range c.ChatIDs {
		if err := c.NotifyRecipient(ctx, alert, chatID); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

func (c Client) NotifyRecipient(ctx context.Context, alert domain.Alert, chatID string) error {
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
		"text":                 message(alert),
		"disable_notification": false,
	}
	body, _ := json.Marshal(payload)
	url := baseURL + "/bot" + c.Token + "/sendMessage"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	response, err := client.Do(req)
	if err != nil {
		return err
	}
	response.Body.Close()
	if response.StatusCode/100 != 2 {
		return fmt.Errorf("telegram returned %s", response.Status)
	}
	return nil
}

func message(alert domain.Alert) string {
	name := map[domain.AlertKind]string{
		domain.AlertLow:        "Низкий уровень глюкозы",
		domain.AlertHigh:       "Высокий уровень глюкозы",
		domain.AlertRapidFall:  "Глюкоза быстро снижается",
		domain.AlertRapidRise:  "Глюкоза быстро повышается",
		domain.AlertSignalLoss: "Нет свежих данных от датчика",
	}[alert.Kind]
	parts := []string{"⚠️ " + name}
	if alert.GlucoseMgDL != nil {
		parts = append(parts, fmt.Sprintf("Значение: %d мг/дл", *alert.GlucoseMgDL))
	}
	parts = append(parts, "Время (UTC): "+alert.OpenedAt.UTC().Format("15:04 02.01.2006"))
	return strings.Join(parts, "\n")
}
