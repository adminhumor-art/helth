package alerts

import (
	"crypto/rand"
	"encoding/hex"
	"time"

	"glucose-monitor/backend/internal/domain"
)

type Thresholds struct {
	LowMgDL                int
	HighMgDL               int
	RapidFallMgDLPerMinute float64
	RapidRiseMgDLPerMinute float64
	RecoveryHysteresisMgDL int
	StaleAfter             time.Duration
}

func DefaultThresholds() Thresholds {
	return Thresholds{
		LowMgDL:                70,
		HighMgDL:               250,
		RapidFallMgDLPerMinute: -3,
		RapidRiseMgDLPerMinute: 3,
		RecoveryHysteresisMgDL: 5,
		StaleAfter:             10 * time.Minute,
	}
}

type ChangeType string

const (
	Opened ChangeType = "opened"
	Closed ChangeType = "closed"
)

type Change struct {
	Type  ChangeType
	Alert domain.Alert
}

// State is a durable snapshot loaded by the Store while holding the patient
// transaction lock. Engine never owns or mutates committed alert state.
type State struct {
	MonitoringStartedAt time.Time
	LatestAt            time.Time
	LatestFreshAt       time.Time
	OpenAlerts          []domain.Alert
}

type Engine struct {
	thresholds Thresholds
	now        func() time.Time
}

func NewEngine(t Thresholds) *Engine {
	return &Engine{thresholds: t, now: time.Now}
}

// PlanMeasurement is a pure decision over a durable pre-transaction snapshot.
// The caller must commit the measurement and every returned change atomically.
func (e *Engine) PlanMeasurement(state State, m domain.Measurement) []Change {
	now := e.now().UTC()
	open := validOpenAlerts(m.PatientID, state.OpenAlerts)
	latestAt := state.LatestAt
	latestFreshAt := state.LatestFreshAt
	if latestFreshAt.IsZero() {
		latestFreshAt = latestAt
	}
	newest := m.Quality == domain.QualityValid && (latestAt.IsZero() || m.SensorTime.After(latestAt))
	var changes []Change

	if newest {
		latestAt = m.SensorTime
		latestFreshAt = m.FreshnessTime()
		if !latestFreshAt.IsZero() && now.Sub(latestFreshAt) < e.thresholds.StaleAfter {
			changes = append(changes, e.planGlucose(open, m, now)...)
		}
	}
	changes = append(changes, e.planSignalLoss(open, m.PatientID, latestFreshAt, state.MonitoringStartedAt, now, true)...)
	return changes
}

// PlanStaleness is also pure. In particular, a persisted signal-loss alert is
// never closed merely because the API process restarted.
func (e *Engine) PlanStaleness(state State, patientID string, at time.Time) []Change {
	open := validOpenAlerts(patientID, state.OpenAlerts)
	latestFreshAt := state.LatestFreshAt
	if latestFreshAt.IsZero() {
		latestFreshAt = state.LatestAt
	}
	return e.planSignalLoss(open, patientID, latestFreshAt, state.MonitoringStartedAt, at.UTC(), false)
}

func (e *Engine) planGlucose(open map[domain.AlertKind]domain.Alert, m domain.Measurement, now time.Time) []Change {
	// A warming-up or degraded value is not reliable enough either to open a
	// glucose alert or to declare an existing alert recovered.
	if m.Quality != domain.QualityValid {
		return nil
	}
	desired := make(map[domain.AlertKind]bool)
	desired[domain.AlertLow] = m.GlucoseMgDL <= e.thresholds.LowMgDL
	desired[domain.AlertHigh] = m.GlucoseMgDL >= e.thresholds.HighMgDL
	desired[domain.AlertRapidFall] = m.TrendMgDLPerMinute <= e.thresholds.RapidFallMgDLPerMinute
	desired[domain.AlertRapidRise] = m.TrendMgDLPerMinute >= e.thresholds.RapidRiseMgDLPerMinute

	var changes []Change
	for _, kind := range []domain.AlertKind{domain.AlertLow, domain.AlertHigh, domain.AlertRapidFall, domain.AlertRapidRise} {
		existing, exists := open[kind]
		if desired[kind] && !exists {
			value := m.GlucoseMgDL
			alert := domain.Alert{
				ID: uuid(), PatientID: m.PatientID, Kind: kind, OpenedAt: now,
				MeasurementID: m.EventID, GlucoseMgDL: &value,
			}
			open[kind] = alert
			changes = append(changes, Change{Type: Opened, Alert: alert})
		} else if exists && e.recovered(kind, m) {
			closedAt := now
			existing.ClosedAt = &closedAt
			delete(open, kind)
			changes = append(changes, Change{Type: Closed, Alert: existing})
		}
	}
	return changes
}

func (e *Engine) planSignalLoss(
	open map[domain.AlertKind]domain.Alert,
	patientID string,
	latestAt time.Time,
	monitoringStartedAt time.Time,
	at time.Time,
	allowRecovery bool,
) []Change {
	reference := latestAt
	if reference.IsZero() {
		reference = monitoringStartedAt
	}
	stale := reference.IsZero() || at.Sub(reference) >= e.thresholds.StaleAfter
	existing, exists := open[domain.AlertSignalLoss]
	if stale && !exists {
		alert := domain.Alert{ID: uuid(), PatientID: patientID, Kind: domain.AlertSignalLoss, OpenedAt: at}
		return []Change{{Type: Opened, Alert: alert}}
	}
	if !stale && exists && allowRecovery {
		closedAt := at
		existing.ClosedAt = &closedAt
		return []Change{{Type: Closed, Alert: existing}}
	}
	return nil
}

func validOpenAlerts(patientID string, persisted []domain.Alert) map[domain.AlertKind]domain.Alert {
	result := make(map[domain.AlertKind]domain.Alert)
	for _, alert := range persisted {
		if alert.ID == "" || alert.PatientID != patientID || alert.OpenedAt.IsZero() || alert.ClosedAt != nil || !supportedAlertKind(alert.Kind) {
			continue
		}
		existing, exists := result[alert.Kind]
		if !exists || alert.OpenedAt.After(existing.OpenedAt) ||
			(alert.OpenedAt.Equal(existing.OpenedAt) && alert.ID < existing.ID) {
			result[alert.Kind] = alert
		}
	}
	return result
}

func (e *Engine) recovered(kind domain.AlertKind, m domain.Measurement) bool {
	switch kind {
	case domain.AlertLow:
		return m.GlucoseMgDL >= e.thresholds.LowMgDL+e.thresholds.RecoveryHysteresisMgDL
	case domain.AlertHigh:
		return m.GlucoseMgDL <= e.thresholds.HighMgDL-e.thresholds.RecoveryHysteresisMgDL
	case domain.AlertRapidFall:
		return m.TrendMgDLPerMinute > e.thresholds.RapidFallMgDLPerMinute
	case domain.AlertRapidRise:
		return m.TrendMgDLPerMinute < e.thresholds.RapidRiseMgDLPerMinute
	default:
		return true
	}
}

func supportedAlertKind(kind domain.AlertKind) bool {
	switch kind {
	case domain.AlertLow, domain.AlertHigh, domain.AlertRapidFall, domain.AlertRapidRise, domain.AlertSignalLoss:
		return true
	default:
		return false
	}
}

func uuid() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	buf := make([]byte, 36)
	hex.Encode(buf[0:8], b[0:4])
	buf[8] = '-'
	hex.Encode(buf[9:13], b[4:6])
	buf[13] = '-'
	hex.Encode(buf[14:18], b[6:8])
	buf[18] = '-'
	hex.Encode(buf[19:23], b[8:10])
	buf[23] = '-'
	hex.Encode(buf[24:36], b[10:16])
	return string(buf)
}
