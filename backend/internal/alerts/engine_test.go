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
	state := State{MonitoringStartedAt: now}

	low := measurement(now, 65, -1)
	changes := engine.PlanMeasurement(state, low)
	if len(changes) != 1 || changes[0].Type != Opened || changes[0].Alert.Kind != domain.AlertLow {
		t.Fatalf("expected one opened low alert, got %#v", changes)
	}
	state = committedState(state, low, changes)
	if changes := engine.PlanMeasurement(state, low); len(changes) != 0 {
		t.Fatalf("duplicate value must not duplicate alert: %#v", changes)
	}

	borderline := measurement(now.Add(time.Minute), 72, 0)
	if changes := engine.PlanMeasurement(state, borderline); len(changes) != 0 {
		t.Fatalf("hysteresis must keep low alert open: %#v", changes)
	}
	state.LatestAt = borderline.SensorTime

	recovered := measurement(now.Add(2*time.Minute), 76, 0)
	changes = engine.PlanMeasurement(state, recovered)
	if len(changes) != 1 || changes[0].Type != Closed || changes[0].Alert.Kind != domain.AlertLow {
		t.Fatalf("expected one closed low alert, got %#v", changes)
	}
}

func TestSignalLossSchedulerOpensButCannotCloseWithoutNewMeasurement(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	state := State{MonitoringStartedAt: base, LatestAt: base}

	changes := engine.PlanStaleness(state, "patient-1", base.Add(11*time.Minute))
	if len(changes) != 1 || changes[0].Alert.Kind != domain.AlertSignalLoss || changes[0].Type != Opened {
		t.Fatalf("expected signal-loss open, got %#v", changes)
	}
	state.OpenAlerts = []domain.Alert{changes[0].Alert}
	state.LatestAt = base.Add(12 * time.Minute)
	changes = engine.PlanStaleness(state, "patient-1", base.Add(12*time.Minute))
	if len(changes) != 0 {
		t.Fatalf("scheduler closed signal loss without a newly committed measurement: %#v", changes)
	}
}

func TestFreshValidMeasurementClosesSignalLoss(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	now := base.Add(12 * time.Minute)
	engine.now = func() time.Time { return now }
	state := State{
		MonitoringStartedAt: base,
		LatestAt:            base,
		OpenAlerts: []domain.Alert{{
			ID: "open-signal-loss", PatientID: "patient-1",
			Kind: domain.AlertSignalLoss, OpenedAt: base.Add(10 * time.Minute),
		}},
	}
	value := measurement(now, 110, 0)
	value.ReceivedAt = now
	changes := engine.PlanMeasurement(state, value)
	if len(changes) != 1 || changes[0].Type != Closed || changes[0].Alert.Kind != domain.AlertSignalLoss {
		t.Fatalf("fresh valid measurement did not close signal loss: %#v", changes)
	}
}

func TestMonitoringBaselineDoesNotReplacePersistedSignalLoss(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC)
	open := domain.Alert{
		ID: "restored-signal-loss", PatientID: "patient-1",
		Kind: domain.AlertSignalLoss, OpenedAt: base.Add(11 * time.Minute),
	}
	state := State{MonitoringStartedAt: base, OpenAlerts: []domain.Alert{open}}

	if changes := engine.PlanStaleness(state, "patient-1", base.Add(12*time.Minute)); len(changes) != 0 {
		t.Fatalf("persisted signal loss must stay open without a measurement: %#v", changes)
	}
}

func TestRestoredOpenAlertPreventsDuplicateAndAllowsRecovery(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	engine.now = func() time.Time { return base.Add(2 * time.Minute) }
	value := 60
	state := State{
		MonitoringStartedAt: base,
		LatestAt:            base,
		OpenAlerts: []domain.Alert{{
			ID: "restored-low", PatientID: "patient-1", Kind: domain.AlertLow,
			OpenedAt: base, GlucoseMgDL: &value,
		}},
	}

	low := measurement(base.Add(time.Minute), 65, -1)
	if changes := engine.PlanMeasurement(state, low); len(changes) != 0 {
		t.Fatalf("restored low alert must not reopen: %#v", changes)
	}
	state.LatestAt = low.SensorTime
	changes := engine.PlanMeasurement(state, measurement(base.Add(2*time.Minute), 76, 0))
	if len(changes) != 1 || changes[0].Type != Closed || changes[0].Alert.ID != "restored-low" {
		t.Fatalf("restored alert must close with its original id: %#v", changes)
	}
}

func TestNonValidMeasurementCannotCloseGlucoseAlert(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	engine.now = func() time.Time { return base.Add(time.Minute) }
	value := 60
	state := State{
		MonitoringStartedAt: base,
		LatestAt:            base,
		OpenAlerts: []domain.Alert{{
			ID: "open-low", PatientID: "patient-1", Kind: domain.AlertLow,
			OpenedAt: base, GlucoseMgDL: &value,
		}},
	}

	notReady := measurement(base.Add(time.Minute), 120, 0)
	notReady.Quality = domain.QualityWarmingUp
	if changes := engine.PlanMeasurement(state, notReady); len(changes) != 0 {
		t.Fatalf("non-valid measurement must not close a glucose alert: %#v", changes)
	}
}

func TestNonValidMeasurementCannotCloseSignalLoss(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	now := base.Add(12 * time.Minute)
	engine.now = func() time.Time { return now }
	state := State{
		MonitoringStartedAt: base,
		LatestAt:            base,
		OpenAlerts: []domain.Alert{{
			ID: "open-signal-loss", PatientID: "patient-1",
			Kind: domain.AlertSignalLoss, OpenedAt: base.Add(10 * time.Minute),
		}},
	}

	notReady := measurement(now, 120, 0)
	notReady.ReceivedAt = now
	notReady.Quality = domain.QualityWarmingUp
	if changes := engine.PlanMeasurement(state, notReady); len(changes) != 0 {
		t.Fatalf("non-valid measurement must not close signal loss: %#v", changes)
	}
}

func TestOldPhoneTimestampCannotCloseSignalLoss(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	base := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	now := base.Add(12 * time.Minute)
	engine.now = func() time.Time { return now }
	state := State{
		MonitoringStartedAt: base,
		LatestAt:            base,
		OpenAlerts: []domain.Alert{{
			ID: "open-signal-loss", PatientID: "patient-1",
			Kind: domain.AlertSignalLoss, OpenedAt: base.Add(10 * time.Minute),
		}},
	}

	value := measurement(now, 120, 0)
	value.PhoneTime = base
	value.ReceivedAt = now
	if changes := engine.PlanMeasurement(state, value); len(changes) != 0 {
		t.Fatalf("measurement with stale phone time must not close signal loss: %#v", changes)
	}
}

func TestBackfillAndOutOfOrderMeasurementsDoNotOpenGlucoseAlerts(t *testing.T) {
	engine := NewEngine(DefaultThresholds())
	now := time.Date(2026, 7, 31, 12, 0, 0, 0, time.UTC)
	engine.now = func() time.Time { return now }
	state := State{MonitoringStartedAt: now.Add(-time.Hour), LatestAt: now}

	oldLow := measurement(now.Add(-30*time.Minute), 55, -4)
	changes := engine.PlanMeasurement(state, oldLow)
	for _, change := range changes {
		if change.Alert.Kind != domain.AlertSignalLoss {
			t.Fatalf("offline backfill opened glucose alert: %#v", changes)
		}
	}

	lateLow := measurement(now.Add(-time.Minute), 50, -5)
	changes = engine.PlanMeasurement(state, lateLow)
	for _, change := range changes {
		if change.Alert.Kind != domain.AlertSignalLoss {
			t.Fatalf("out-of-order measurement changed glucose alarms: %#v", changes)
		}
	}
}

func committedState(state State, measurement domain.Measurement, changes []Change) State {
	state.LatestAt = measurement.SensorTime
	byKind := make(map[domain.AlertKind]domain.Alert)
	for _, alert := range state.OpenAlerts {
		byKind[alert.Kind] = alert
	}
	for _, change := range changes {
		switch change.Type {
		case Opened:
			byKind[change.Alert.Kind] = change.Alert
		case Closed:
			delete(byKind, change.Alert.Kind)
		}
	}
	state.OpenAlerts = state.OpenAlerts[:0]
	for _, alert := range byKind {
		state.OpenAlerts = append(state.OpenAlerts, alert)
	}
	return state
}

func measurement(at time.Time, glucose int, trend float64) domain.Measurement {
	return domain.Measurement{
		EventID: "event-1", PatientID: "patient-1", SensorID: "sim-1",
		SensorFamily: domain.SensorSimulator, SensorTime: at, PhoneTime: at,
		GlucoseMgDL: glucose, TrendMgDLPerMinute: trend, Quality: domain.QualityValid,
	}
}
