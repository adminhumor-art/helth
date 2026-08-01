package delivery

import (
	"context"
	"log/slog"
	"time"

	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

type Sender interface {
	NotifyRecipient(context.Context, domain.Alert, string) error
}

type Worker struct {
	store  store.Store
	sender Sender
	logger *slog.Logger
}

func NewWorker(values store.Store, sender Sender, logger *slog.Logger) *Worker {
	if logger == nil {
		logger = slog.Default()
	}
	return &Worker{store: values, sender: sender, logger: logger}
}

func (w *Worker) RunOnce(ctx context.Context, at time.Time) {
	deliveries, err := w.store.DueAlertDeliveries(ctx, at, 50)
	if err != nil {
		w.logger.Error("load alert deliveries", "error", err)
		return
	}
	for _, value := range deliveries {
		if err := w.sender.NotifyRecipient(ctx, value.Alert, value.Recipient); err != nil {
			next := at.Add(retryDelay(value.Attempts + 1))
			if markErr := w.store.MarkAlertDeliveryFailed(ctx, value.ID, next, truncate(err.Error(), 1_000)); markErr != nil {
				w.logger.Error("mark alert delivery failed", "error", markErr, "delivery_id", value.ID)
			}
			continue
		}
		if err := w.store.MarkAlertDeliverySent(ctx, value.ID, at); err != nil {
			w.logger.Error("mark alert delivery sent", "error", err, "delivery_id", value.ID)
		}
	}
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
	if len(value) <= limit {
		return value
	}
	return value[:limit]
}
