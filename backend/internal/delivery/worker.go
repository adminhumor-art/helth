package delivery

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"strings"
	"time"

	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

type Sender interface {
	NotifyRecipient(context.Context, domain.Alert, string, string) error
}

type Worker struct {
	store  store.Store
	sender Sender
	logger *slog.Logger
	now    func() time.Time
}

func NewWorker(values store.Store, sender Sender, logger *slog.Logger) *Worker {
	if logger == nil {
		logger = slog.Default()
	}
	return &Worker{store: values, sender: sender, logger: logger, now: time.Now}
}

func (w *Worker) RunOnce(ctx context.Context, at time.Time) {
	leaseToken, err := newLeaseToken()
	if err != nil {
		w.logger.Error("create alert delivery lease", "error", err)
		return
	}
	deliveries, err := w.store.ClaimDueAlertDeliveries(ctx, at, 10, leaseToken, at.Add(2*time.Minute))
	if err != nil {
		w.logger.Error("load alert deliveries", "error", err)
		return
	}
	for _, value := range deliveries {
		if err := w.sender.NotifyRecipient(ctx, value.Alert, value.PatientDisplayName, value.Recipient); err != nil {
			completedAt := w.now().UTC()
			next := completedAt.Add(retryDelay(value.Attempts + 1))
			if markErr := w.store.MarkAlertDeliveryFailed(
				ctx, value.ID, leaseToken, completedAt, next, truncate(err.Error(), 1_000),
			); markErr != nil {
				w.logger.Error("mark alert delivery failed", "error", markErr, "delivery_id", value.ID)
			}
			continue
		}
		if err := w.store.MarkAlertDeliverySent(ctx, value.ID, leaseToken, w.now().UTC()); err != nil {
			w.logger.Error("mark alert delivery sent", "error", err, "delivery_id", value.ID)
		}
	}
}

func newLeaseToken() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(value[:]), nil
}

func retryDelay(attempt int) time.Duration {
	delay := 5 * time.Second
	for i := 1; i < attempt && delay < 10*time.Minute; i++ {
		delay *= 2
	}
	if delay > 10*time.Minute {
		return 10 * time.Minute
	}
	return delay
}

func truncate(value string, limit int) string {
	if limit <= 0 {
		return ""
	}
	if len(value) <= limit {
		return strings.ToValidUTF8(value, "")
	}
	return strings.ToValidUTF8(value[:limit], "")
}
