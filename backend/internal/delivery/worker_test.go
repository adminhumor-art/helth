package delivery

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"glucose-monitor/backend/internal/domain"
	"glucose-monitor/backend/internal/store"
)

func TestWorkerRetriesThenMarksDeliverySent(t *testing.T) {
	ctx := context.Background()
	values := store.NewMemory()
	now := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000020", PatientID: "patient-1",
		Kind: domain.AlertLow, OpenedAt: now,
	}
	if err := values.SaveAlert(ctx, alert, []string{"family-chat"}); err != nil {
		t.Fatal(err)
	}
	sender := &flakySender{failures: 1}
	worker := NewWorker(values, sender, slog.New(slog.NewTextHandler(io.Discard, nil)))

	worker.RunOnce(ctx, now)
	if sender.calls != 1 {
		t.Fatalf("expected first attempt, got %d", sender.calls)
	}
	if due, _ := values.DueAlertDeliveries(ctx, now.Add(4*time.Second), 10); len(due) != 0 {
		t.Fatalf("failed delivery must wait for backoff: %#v", due)
	}
	worker.RunOnce(ctx, now.Add(5*time.Second))
	if sender.calls != 2 {
		t.Fatalf("expected retry, got %d calls", sender.calls)
	}
	if due, _ := values.DueAlertDeliveries(ctx, now.Add(time.Hour), 10); len(due) != 0 {
		t.Fatalf("sent delivery must leave the queue: %#v", due)
	}
}

type flakySender struct {
	calls    int
	failures int
}

func (f *flakySender) NotifyRecipient(_ context.Context, _ domain.Alert, _ string) error {
	f.calls++
	if f.calls <= f.failures {
		return errors.New("temporary failure")
	}
	return nil
}
