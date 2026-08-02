package delivery

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"strings"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf8"

	"glucose-monitor/backend/internal/alerts"
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
	activateMonitoring(t, values, alert.PatientID, now)
	if err := values.ProcessStaleness(ctx, alert.PatientID, now, []string{"family-chat"}, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	sender := &flakySender{failures: 1}
	worker := NewWorker(values, sender, slog.New(slog.NewTextHandler(io.Discard, nil)))
	current := now
	worker.now = func() time.Time { return current }

	worker.RunOnce(ctx, now)
	if sender.calls != 1 {
		t.Fatalf("expected first attempt, got %d", sender.calls)
	}
	if sender.patientDisplayName != "Пациент" {
		t.Fatalf("worker did not pass the delivery patient name: %q", sender.patientDisplayName)
	}
	if due, _ := values.ClaimDueAlertDeliveries(ctx, now.Add(4*time.Second), 10, "early-inspection", now.Add(time.Minute)); len(due) != 0 {
		t.Fatalf("failed delivery must wait for backoff: %#v", due)
	}
	current = now.Add(5 * time.Second)
	worker.RunOnce(ctx, current)
	if sender.calls != 2 {
		t.Fatalf("expected retry, got %d calls", sender.calls)
	}
	if due, _ := values.ClaimDueAlertDeliveries(ctx, now.Add(time.Hour), 10, "sent-inspection", now.Add(2*time.Hour)); len(due) != 0 {
		t.Fatalf("sent delivery must leave the queue: %#v", due)
	}
}

func TestConcurrentWorkersDoNotSendSameDelivery(t *testing.T) {
	ctx := context.Background()
	values := store.NewMemory()
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	alert := domain.Alert{
		ID: "00000000-0000-4000-8000-000000000021", PatientID: "patient-1",
		Kind: domain.AlertLow, OpenedAt: now,
	}
	activateMonitoring(t, values, alert.PatientID, now)
	if err := values.ProcessStaleness(ctx, alert.PatientID, now, []string{"family-chat"}, func(alerts.State, string, time.Time) []alerts.Change {
		return []alerts.Change{{Type: alerts.Opened, Alert: alert}}
	}); err != nil {
		t.Fatal(err)
	}
	sender := &countingSender{started: make(chan struct{}), release: make(chan struct{})}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	workers := []*Worker{NewWorker(values, sender, logger), NewWorker(values, sender, logger)}
	for _, worker := range workers {
		worker.now = func() time.Time { return now }
	}
	firstDone := make(chan struct{})
	go func() {
		workers[0].RunOnce(ctx, now)
		close(firstDone)
	}()
	select {
	case <-sender.started:
	case <-time.After(time.Second):
		t.Fatal("first worker did not start sending")
	}
	secondDone := make(chan struct{})
	go func() {
		workers[1].RunOnce(ctx, now)
		close(secondDone)
	}()
	select {
	case <-secondDone:
	case <-time.After(time.Second):
		t.Fatal("second worker blocked on an already leased delivery")
	}
	if calls := sender.calls.Load(); calls != 1 {
		t.Fatalf("same leased delivery was sent %d times", calls)
	}
	close(sender.release)
	<-firstDone
}

func TestStoredDeliveryErrorTruncationPreservesUTF8(t *testing.T) {
	value := "a" + strings.Repeat("🙂", 300)
	truncated := truncate(value, 1_000)
	if len(truncated) > 1_000 {
		t.Fatalf("truncated error has %d bytes", len(truncated))
	}
	if !utf8.ValidString(truncated) {
		t.Fatal("truncated error is not valid UTF-8 and cannot be stored in PostgreSQL text")
	}
}

type flakySender struct {
	calls              int
	failures           int
	patientDisplayName string
}

type countingSender struct {
	calls   atomic.Int32
	started chan struct{}
	release chan struct{}
}

func activateMonitoring(t *testing.T, values *store.Memory, patientID string, at time.Time) {
	t.Helper()
	_, err := values.ProcessMeasurement(context.Background(), domain.Measurement{
		EventID: "activation-event", PatientID: patientID, SensorID: "activation-sensor",
		SensorFamily: domain.SensorSibionicsGS1, SensorTime: at, PhoneTime: at, ReceivedAt: at,
		GlucoseMgDL: 110, Quality: domain.QualityValid,
	}, nil, func(alerts.State, domain.Measurement) []alerts.Change { return nil })
	if err != nil {
		t.Fatal(err)
	}
}

func (s *countingSender) NotifyRecipient(context.Context, domain.Alert, string, string) error {
	if s.calls.Add(1) == 1 {
		close(s.started)
	}
	<-s.release
	return nil
}

func (f *flakySender) NotifyRecipient(_ context.Context, _ domain.Alert, patientDisplayName, _ string) error {
	f.calls++
	f.patientDisplayName = patientDisplayName
	if f.calls <= f.failures {
		return errors.New("temporary failure")
	}
	return nil
}
