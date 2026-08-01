package alerts

import (
	"testing"
	"time"

	"glucose-monitor/backend/internal/domain"
)

func TestLowAlertIsDeduplicatedAndRecoversWithHysteresis(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	now := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	engine.now = func() time.Time { return now }

	low := measurement(now, 65, -1)
	changes := engine.Evaluate(low)
	if len(changes) != 1 || changes[0].Type != Opened || changes[0].Alert.Kind != domain.AlertLow {
		t.Fatalf("expected one opened low alert, got %#v", changes)
	}
	if changes := engine.Evaluate(low); len(changes) != 0 {
		t.Fatalf("duplicate value must not duplicate alert: %#v", changes)
	}

	borderline := measurement(now.Add(time.Minute), 72, 0)
	if changes := engine.Evaluate(borderline); len(changes) != 0 {
		t.Fatalf("hysteresis must keep low alert open: %#v", changes)
	}

	recovered := measurement(now.Add(2*time.Minute), 76, 0)
	changes = engine.Evaluate(recovered)
	if len(changes) != 1 || changes[0].Type != Closed || changes[0].Alert.Kind != domain.AlertLow {
		t.Fatalf("expected one closed low alert, got %#v", changes)
	}
}

func TestSignalLossOpensAndCloses(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	engine.Evaluate(measurement(base, 110, 0))

	changes := engine.EvaluateStaleness("patient-1", base.Add(11*time.Minute))
	if len(changes) != 1 || changes[0].Alert.Kind != domain.AlertSignalLoss || changes[0].Type != Opened {
		t.Fatalf("expected signal-loss open, got %#v", changes)
	}

	engine.Evaluate(measurement(base.Add(12*time.Minute), 112, 0))
	changes = engine.EvaluateStaleness("patient-1", base.Add(12*time.Minute))
	if len(changes) != 1 || changes[0].Type != Closed {
		t.Fatalf("expected signal-loss close, got %#v", changes)
	}
}

func TestSeedLatestUsesNewestTimestamp(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	engine.SeedLatest("patient-1", base)
	engine.SeedLatest("patient-1", base.Add(-time.Hour))

	if changes := engine.EvaluateStaleness("patient-1", base.Add(9*time.Minute)); len(changes) != 0 {
		t.Fatalf("patient must still be fresh: %#v", changes)
	}
	changes := engine.EvaluateStaleness("patient-1", base.Add(11*time.Minute))
	if len(changes) != 1 || changes[0].Alert.Kind != domain.AlertSignalLoss {
		t.Fatalf("expected signal loss after stale window, got %#v", changes)
	}
}

func TestBackfillAndOutOfOrderMeasurementsDoNotOpenAlerts(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	now := time.Date(2026, 7, 31, 12, 0, 0, 0, time.UTC)
	engine.now = func() time.Time { return now }

	oldLow := measurement(now.Add(-30*time.Minute), 55, -4)
	if changes := engine.Evaluate(oldLow); len(changes) != 0 {
		t.Fatalf("offline backfill must not open a live alert: %#v", changes)
	}
	if changes := engine.Evaluate(measurement(now, 110, 0)); len(changes) != 0 {
		t.Fatalf("fresh normal measurement must remain normal: %#v", changes)
	}
	lateLow := measurement(now.Add(-time.Minute), 50, -5)
	if changes := engine.Evaluate(lateLow); len(changes) != 0 {
		t.Fatalf("out-of-order measurement must not change current alarms: %#v", changes)
	}
}

func measurement(at time.Time, glucose int, trend float64) domain.Measurement {
	return domain.Measurement{
		EventID: "event-1", PatientID: "patient-1", SensorID: "sim-1",
		SensorFamily: domain.SensorSimulator, SensorTime: at, PhoneTime: at,
		GlucoseMgDL: glucose, TrendMgDLPerMinute: trend, Quality: domain.QualityValid,
	}
}
