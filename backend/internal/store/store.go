package store

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	alertpolicy "glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
)

var ErrNotFound = errors.New("not found")
var ErrEventConflict = errors.New("event id conflicts with stored measurement")
var ErrInvalidAlertPlan = errors.New("invalid alert transaction plan")

type MeasurementAlertPlanner func(alertpolicy.State, domain.Measurement) []alertpolicy.Change
type StalenessAlertPlanner func(alertpolicy.State, string, time.Time) []alertpolicy.Change

type Store interface {
	PrimePatient(context.Context, string, time.Time) error
	ProcessMeasurement(context.Context, domain.Measurement, []string, MeasurementAlertPlanner) (duplicate bool, err error)
	ProcessStaleness(context.Context, string, time.Time, []string, StalenessAlertPlanner) error
	PatientSnapshot(context.Context, string) (domain.PatientSnapshot, error)
	Latest(context.Context, string) (*domain.Measurement, error)
	List(context.Context, string, time.Time, time.Time) ([]domain.Measurement, error)
	OpenAlerts(context.Context, string) ([]domain.Alert, error)
	AcknowledgeAlert(context.Context, string, string, time.Time) error
	ClaimDueAlertDeliveries(context.Context, time.Time, int, string, time.Time) ([]domain.AlertDelivery, error)
	MarkAlertDeliverySent(context.Context, string, string, time.Time) error
	MarkAlertDeliveryFailed(context.Context, string, string, time.Time, time.Time, string) error
}

type Memory struct {
	mu                sync.RWMutex
	measurements      map[string]domain.Measurement
	measurementBySeq  map[measurementSequenceKey]string
	byPatient         map[string][]string
	alerts            map[string]domain.Alert
	deliveries        map[string]memoryDelivery
	monitoringStarted map[string]time.Time
}

type measurementSequenceKey struct {
	PatientID string
	SensorID  string
	Sequence  uint64
}

type memoryDelivery struct {
	ID             string
	AlertID        string
	Recipient      string
	Status         string
	Attempts       int
	NextAttemptAt  time.Time
	LeaseToken     string
	LeaseExpiresAt time.Time
}

func NewMemory() *Memory {
	return &Memory{
		measurements:      make(map[string]domain.Measurement),
		measurementBySeq:  make(map[measurementSequenceKey]string),
		byPatient:         make(map[string][]string),
		alerts:            make(map[string]domain.Alert),
		deliveries:        make(map[string]memoryDelivery),
		monitoringStarted: make(map[string]time.Time),
	}
}

func (m *Memory) PrimePatient(_ context.Context, patientID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	existing := m.monitoringStarted[patientID]
	if existing.IsZero() || at.Before(existing) {
		m.monitoringStarted[patientID] = at.UTC()
	}
	return nil
}

func (m *Memory) ProcessMeasurement(
	_ context.Context,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if existing, ok := m.measurements[value.EventID]; ok {
		if !sameMeasurementPayload(existing, value) {
			return false, ErrEventConflict
		}
		return true, nil
	}
	sequenceKey := measurementSequenceKey{
		PatientID: value.PatientID,
		SensorID:  value.SensorID,
		Sequence:  value.Sequence,
	}
	if _, exists := m.measurementBySeq[sequenceKey]; exists {
		return false, ErrEventConflict
	}
	startedAt := m.monitoringStarted[value.PatientID]
	if startedAt.IsZero() {
		startedAt = value.ReceivedAt.UTC()
	}
	state := m.alertStateLocked(value.PatientID)
	state.MonitoringStartedAt = startedAt
	changes := planner(state, value)
	if err := validateAlertChanges(value.PatientID, state.OpenAlerts, changes); err != nil {
		return false, err
	}

	m.measurements[value.EventID] = value
	m.measurementBySeq[sequenceKey] = value.EventID
	m.byPatient[value.PatientID] = append(m.byPatient[value.PatientID], value.EventID)
	if m.monitoringStarted[value.PatientID].IsZero() {
		m.monitoringStarted[value.PatientID] = startedAt
	}
	m.applyAlertChangesLocked(changes, recipients)
	return false, nil
}

func (m *Memory) ProcessStaleness(
	_ context.Context,
	patientID string,
	at time.Time,
	recipients []string,
	planner StalenessAlertPlanner,
) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	startedAt := m.monitoringStarted[patientID]
	if startedAt.IsZero() {
		startedAt = at.UTC()
	}
	state := m.alertStateLocked(patientID)
	state.MonitoringStartedAt = startedAt
	changes := planner(state, patientID, at)
	if err := validateAlertChanges(patientID, state.OpenAlerts, changes); err != nil {
		return err
	}
	if m.monitoringStarted[patientID].IsZero() {
		m.monitoringStarted[patientID] = startedAt
	}
	m.applyAlertChangesLocked(changes, recipients)
	return nil
}

func sameMeasurementPayload(left, right domain.Measurement) bool {
	return left.EventID == right.EventID &&
		left.PatientID == right.PatientID &&
		left.SensorID == right.SensorID &&
		left.SensorFamily == right.SensorFamily &&
		left.SensorTime.Equal(right.SensorTime) &&
		left.PhoneTime.Equal(right.PhoneTime) &&
		left.GlucoseMgDL == right.GlucoseMgDL &&
		left.TrendMgDLPerMinute == right.TrendMgDLPerMinute &&
		left.Quality == right.Quality &&
		left.Sequence == right.Sequence
}

func (m *Memory) Latest(_ context.Context, patientID string) (*domain.Measurement, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	ids := m.byPatient[patientID]
	if len(ids) == 0 {
		return nil, ErrNotFound
	}
	latest := m.measurements[ids[0]]
	for _, id := range ids[1:] {
		candidate := m.measurements[id]
		if candidate.SensorTime.After(latest.SensorTime) {
			latest = candidate
		}
	}
	return &latest, nil
}

func (m *Memory) PatientSnapshot(_ context.Context, patientID string) (domain.PatientSnapshot, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	snapshot := domain.PatientSnapshot{PatientID: patientID, OpenAlerts: make([]domain.Alert, 0)}
	for _, id := range m.byPatient[patientID] {
		candidate := m.measurements[id]
		if candidate.Quality != domain.QualityValid {
			continue
		}
		if snapshot.Latest == nil || candidate.SensorTime.After(snapshot.Latest.SensorTime) {
			value := candidate
			snapshot.Latest = &value
		}
	}
	for _, alert := range m.alerts {
		if alert.PatientID == patientID && alert.ClosedAt == nil {
			snapshot.OpenAlerts = append(snapshot.OpenAlerts, alert)
		}
	}
	sort.Slice(snapshot.OpenAlerts, func(i, j int) bool {
		if snapshot.OpenAlerts[i].OpenedAt.Equal(snapshot.OpenAlerts[j].OpenedAt) {
			return snapshot.OpenAlerts[i].ID < snapshot.OpenAlerts[j].ID
		}
		return snapshot.OpenAlerts[i].OpenedAt.Before(snapshot.OpenAlerts[j].OpenedAt)
	})
	return snapshot, nil
}

func (m *Memory) List(_ context.Context, patientID string, from, to time.Time) ([]domain.Measurement, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.Measurement, 0)
	for _, id := range m.byPatient[patientID] {
		value := m.measurements[id]
		if !value.SensorTime.Before(from) && !value.SensorTime.After(to) {
			result = append(result, value)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].SensorTime.Before(result[j].SensorTime) })
	return result, nil
}

func (m *Memory) OpenAlerts(_ context.Context, patientID string) ([]domain.Alert, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.Alert, 0)
	for _, alert := range m.alerts {
		if alert.PatientID == patientID && alert.ClosedAt == nil {
			result = append(result, alert)
		}
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].OpenedAt.Equal(result[j].OpenedAt) {
			return result[i].ID < result[j].ID
		}
		return result[i].OpenedAt.Before(result[j].OpenedAt)
	})
	return result, nil
}

func (m *Memory) alertStateLocked(patientID string) alertpolicy.State {
	state := alertpolicy.State{MonitoringStartedAt: m.monitoringStarted[patientID]}
	for _, eventID := range m.byPatient[patientID] {
		candidate := m.measurements[eventID]
		if candidate.Quality != domain.QualityValid {
			continue
		}
		if candidate.SensorTime.After(state.LatestAt) {
			state.LatestAt = candidate.SensorTime
			state.LatestFreshAt = candidate.FreshnessTime()
		}
	}
	for _, alert := range m.alerts {
		if alert.PatientID == patientID && alert.ClosedAt == nil {
			state.OpenAlerts = append(state.OpenAlerts, alert)
		}
	}
	sort.Slice(state.OpenAlerts, func(i, j int) bool {
		if state.OpenAlerts[i].OpenedAt.Equal(state.OpenAlerts[j].OpenedAt) {
			return state.OpenAlerts[i].ID < state.OpenAlerts[j].ID
		}
		return state.OpenAlerts[i].OpenedAt.Before(state.OpenAlerts[j].OpenedAt)
	})
	return state
}

func (m *Memory) applyAlertChangesLocked(changes []alertpolicy.Change, recipients []string) {
	for _, change := range changes {
		alert := change.Alert
		if existing, ok := m.alerts[alert.ID]; ok && alert.AcknowledgedAt == nil {
			alert.AcknowledgedAt = existing.AcknowledgedAt
		}
		m.alerts[alert.ID] = alert
		if change.Type != alertpolicy.Opened {
			continue
		}
		for _, recipient := range recipients {
			recipient = strings.TrimSpace(recipient)
			if recipient == "" {
				continue
			}
			id := deliveryID(alert.ID, recipient)
			if _, exists := m.deliveries[id]; !exists {
				m.deliveries[id] = memoryDelivery{
					ID: id, AlertID: alert.ID, Recipient: recipient,
					Status: "pending", NextAttemptAt: alert.OpenedAt,
				}
			}
		}
	}
}

func validateAlertChanges(patientID string, current []domain.Alert, changes []alertpolicy.Change) error {
	open := make(map[domain.AlertKind]domain.Alert)
	for _, alert := range current {
		if alert.PatientID != patientID || alert.ID == "" || alert.ClosedAt != nil {
			return fmt.Errorf("%w: malformed current open alert", ErrInvalidAlertPlan)
		}
		if _, exists := open[alert.Kind]; exists {
			return fmt.Errorf("%w: duplicate current kind %s", ErrInvalidAlertPlan, alert.Kind)
		}
		open[alert.Kind] = alert
	}
	for _, change := range changes {
		alert := change.Alert
		if alert.ID == "" || alert.PatientID != patientID || alert.OpenedAt.IsZero() {
			return fmt.Errorf("%w: malformed changed alert", ErrInvalidAlertPlan)
		}
		switch change.Type {
		case alertpolicy.Opened:
			if alert.ClosedAt != nil {
				return fmt.Errorf("%w: opened alert is closed", ErrInvalidAlertPlan)
			}
			if _, exists := open[alert.Kind]; exists {
				return fmt.Errorf("%w: kind %s is already open", ErrInvalidAlertPlan, alert.Kind)
			}
			open[alert.Kind] = alert
		case alertpolicy.Closed:
			existing, exists := open[alert.Kind]
			if !exists || existing.ID != alert.ID || alert.ClosedAt == nil {
				return fmt.Errorf("%w: closing alert does not match open state", ErrInvalidAlertPlan)
			}
			delete(open, alert.Kind)
		default:
			return fmt.Errorf("%w: unknown change type %q", ErrInvalidAlertPlan, change.Type)
		}
	}
	return nil
}

func (m *Memory) AcknowledgeAlert(_ context.Context, patientID, alertID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	alert, ok := m.alerts[alertID]
	if !ok || alert.PatientID != patientID {
		return ErrNotFound
	}
	if alert.AcknowledgedAt == nil {
		alert.AcknowledgedAt = &at
		m.alerts[alertID] = alert
	}
	return nil
}

func (m *Memory) ClaimDueAlertDeliveries(
	_ context.Context,
	at time.Time,
	limit int,
	leaseToken string,
	leaseExpiresAt time.Time,
) ([]domain.AlertDelivery, error) {
	if limit <= 0 || strings.TrimSpace(leaseToken) == "" || !leaseExpiresAt.After(at) {
		return nil, errors.New("invalid alert delivery lease")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	candidates := make([]memoryDelivery, 0)
	for _, delivery := range m.deliveries {
		if delivery.Status != "pending" || delivery.NextAttemptAt.After(at) ||
			(delivery.LeaseToken != "" && delivery.LeaseExpiresAt.After(at)) {
			continue
		}
		candidates = append(candidates, delivery)
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].NextAttemptAt.Equal(candidates[j].NextAttemptAt) {
			return candidates[i].ID < candidates[j].ID
		}
		return candidates[i].NextAttemptAt.Before(candidates[j].NextAttemptAt)
	})
	if len(candidates) > limit {
		candidates = candidates[:limit]
	}
	result := make([]domain.AlertDelivery, 0, len(candidates))
	for _, delivery := range candidates {
		delivery.LeaseToken = leaseToken
		delivery.LeaseExpiresAt = leaseExpiresAt
		m.deliveries[delivery.ID] = delivery
		result = append(result, domain.AlertDelivery{
			ID: delivery.ID, Alert: m.alerts[delivery.AlertID],
			Recipient: delivery.Recipient, Attempts: delivery.Attempts,
		})
	}
	return result, nil
}

func (m *Memory) MarkAlertDeliverySent(_ context.Context, id, leaseToken string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delivery, ok := m.deliveries[id]
	if !ok || delivery.Status != "pending" || delivery.LeaseToken != leaseToken || !delivery.LeaseExpiresAt.After(at) {
		return ErrNotFound
	}
	delivery.Status = "sent"
	delivery.Attempts++
	delivery.LeaseToken = ""
	delivery.LeaseExpiresAt = time.Time{}
	m.deliveries[id] = delivery
	return nil
}

func (m *Memory) MarkAlertDeliveryFailed(_ context.Context, id, leaseToken string, at, next time.Time, _ string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delivery, ok := m.deliveries[id]
	if !ok || delivery.Status != "pending" || delivery.LeaseToken != leaseToken || !delivery.LeaseExpiresAt.After(at) {
		return ErrNotFound
	}
	delivery.Attempts++
	delivery.NextAttemptAt = next
	delivery.LeaseToken = ""
	delivery.LeaseExpiresAt = time.Time{}
	m.deliveries[id] = delivery
	return nil
}

func deliveryID(alertID, recipient string) string {
	sum := sha256.Sum256([]byte(alertID + "\x00telegram\x00" + recipient))
	sum[6] = (sum[6] & 0x0f) | 0x50
	sum[8] = (sum[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", sum[0:4], sum[4:6], sum[6:8], sum[8:10], sum[10:16])
}
