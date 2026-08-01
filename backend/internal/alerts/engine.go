package alerts

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
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

type patientState struct {
	latestAt time.Time
	open     map[domain.AlertKind]domain.Alert
}

type Engine struct {
	mu         sync.Mutex
	thresholds Thresholds
	patients   map[string]*patientState
	now        func() time.Time
}

func NewEngine(t Thresholds) *Engine {
	return &Engine{thresholds: t, patients: make(map[string]*patientState), now: time.Now}
}

// SeedLatest restores the most recent known sensor time after a process restart.
// It never moves the clock backwards, so an older database row cannot make a
// currently connected patient appear stale.
func (e *Engine) SeedLatest(patientID string, at time.Time) {
	e.mu.Lock()
	defer e.mu.Unlock()
	state := e.state(patientID)
	if at.After(state.latestAt) {
		state.latestAt = at
	}
}

func (e *Engine) Evaluate(m domain.Measurement) []Change {
	e.mu.Lock()
	defer e.mu.Unlock()

	state := e.state(m.PatientID)
	if !state.latestAt.IsZero() && !m.SensorTime.After(state.latestAt) {
		return nil
	}
	state.latestAt = m.SensorTime
	if e.now().Sub(m.SensorTime) >= e.thresholds.StaleAfter {
		return nil
	}

	desired := make(map[domain.AlertKind]bool)
	if m.Quality == domain.QualityValid {
		desired[domain.AlertLow] = m.GlucoseMgDL <= e.thresholds.LowMgDL
		desired[domain.AlertHigh] = m.GlucoseMgDL >= e.thresholds.HighMgDL
		desired[domain.AlertRapidFall] = m.TrendMgDLPerMinute <= e.thresholds.RapidFallMgDLPerMinute
		desired[domain.AlertRapidRise] = m.TrendMgDLPerMinute >= e.thresholds.RapidRiseMgDLPerMinute
	}

	var changes []Change
	for _, kind := range []domain.AlertKind{domain.AlertLow, domain.AlertHigh, domain.AlertRapidFall, domain.AlertRapidRise} {
		active := desired[kind]
		if existing, ok := state.open[kind]; active && !ok {
			value := m.GlucoseMgDL
			alert := domain.Alert{
				ID: uuid(), PatientID: m.PatientID, Kind: kind, OpenedAt: e.now().UTC(),
				MeasurementID: m.EventID, GlucoseMgDL: &value,
			}
			state.open[kind] = alert
			changes = append(changes, Change{Type: Opened, Alert: alert})
		} else if ok && e.recovered(kind, m) {
			closedAt := e.now().UTC()
			existing.ClosedAt = &closedAt
			delete(state.open, kind)
			changes = append(changes, Change{Type: Closed, Alert: existing})
		}
	}
	return changes
}

func (e *Engine) EvaluateStaleness(patientID string, at time.Time) []Change {
	e.mu.Lock()
	defer e.mu.Unlock()

	state := e.state(patientID)
	stale := state.latestAt.IsZero() || at.Sub(state.latestAt) >= e.thresholds.StaleAfter
	existing, open := state.open[domain.AlertSignalLoss]
	if stale && !open {
		alert := domain.Alert{ID: uuid(), PatientID: patientID, Kind: domain.AlertSignalLoss, OpenedAt: at.UTC()}
		state.open[domain.AlertSignalLoss] = alert
		return []Change{{Type: Opened, Alert: alert}}
	}
	if !stale && open {
		closedAt := at.UTC()
		existing.ClosedAt = &closedAt
		delete(state.open, domain.AlertSignalLoss)
		return []Change{{Type: Closed, Alert: existing}}
	}
	return nil
}

func (e *Engine) OpenAlerts(patientID string) []domain.Alert {
	e.mu.Lock()
	defer e.mu.Unlock()
	state := e.state(patientID)
	result := make([]domain.Alert, 0, len(state.open))
	for _, alert := range state.open {
		result = append(result, alert)
	}
	return result
}

func (e *Engine) state(patientID string) *patientState {
	state, ok := e.patients[patientID]
	if !ok {
		state = &patientState{open: make(map[domain.AlertKind]domain.Alert)}
		e.patients[patientID] = state
	}
	return state
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
