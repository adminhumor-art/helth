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
	ResolveActiveDevice(context.Context, []byte, time.Time) (DeviceAccess, error)
	ProvisionDeviceActivation(context.Context, DeviceActivationProvisioning) error
	ConsumeDeviceActivation(context.Context, DeviceActivationConsume) (DeviceAccess, error)
	ResolveActiveFamilySession(context.Context, []byte, time.Time) (FamilySessionAccess, error)
	IssueFamilyWebSession(context.Context, []byte, FamilyWebSessionCredential, time.Time) (FamilyWebSessionAccess, error)
	ResolveActiveFamilyWebSession(context.Context, []byte, time.Time) (FamilyWebSessionAccess, error)
	HouseholdCanAccessPatient(context.Context, string, string) (bool, error)
	TelegramRecipients(context.Context, string) ([]string, error)
	HasTelegramRecipients(context.Context) (bool, error)
	ValidateProductionAccess(context.Context, time.Time) error
	PatientIDs(context.Context) ([]string, error)
	ProcessMeasurement(context.Context, domain.Measurement, []string, MeasurementAlertPlanner) (duplicate bool, err error)
	ProcessDeviceMeasurement(context.Context, DeviceAccess, domain.Measurement, []string, MeasurementAlertPlanner) (duplicate bool, err error)
	ProcessStaleness(context.Context, string, time.Time, []string, StalenessAlertPlanner) error
	PatientSnapshot(context.Context, string) (domain.PatientSnapshot, error)
	Latest(context.Context, string) (*domain.Measurement, error)
	List(context.Context, string, time.Time, time.Time) ([]domain.Measurement, error)
	OpenAlerts(context.Context, string) ([]domain.Alert, error)
	AcknowledgeAlert(context.Context, string, string, time.Time) error
	AcknowledgeAlertForHousehold(context.Context, string, string, time.Time) error
	ClaimDueAlertDeliveries(context.Context, time.Time, int, string, time.Time) ([]domain.AlertDelivery, error)
	MarkAlertDeliverySent(context.Context, string, string, time.Time) error
	MarkAlertDeliveryFailed(context.Context, string, string, time.Time, time.Time, string) error
}

type Memory struct {
	mu                          sync.RWMutex
	measurements                map[string]domain.Measurement
	measurementBySeq            map[measurementSequenceKey]string
	byPatient                   map[string][]string
	alerts                      map[string]domain.Alert
	deliveries                  map[string]memoryDelivery
	monitoringStarted           map[string]time.Time
	devices                     map[string]memoryDeviceAccess
	deviceActivations           map[string]memoryDeviceActivationCredential
	familySessions              map[string]memoryFamilySessionAccess
	familyWebSessions           map[string]memoryFamilyWebSessionAccess
	patientHouseholds           map[string]string
	householdNames              map[string]string
	patientNames                map[string]string
	householdTelegramRecipients map[string][]string
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
		measurements:                make(map[string]domain.Measurement),
		measurementBySeq:            make(map[measurementSequenceKey]string),
		byPatient:                   make(map[string][]string),
		alerts:                      make(map[string]domain.Alert),
		deliveries:                  make(map[string]memoryDelivery),
		monitoringStarted:           make(map[string]time.Time),
		devices:                     make(map[string]memoryDeviceAccess),
		deviceActivations:           make(map[string]memoryDeviceActivationCredential),
		familySessions:              make(map[string]memoryFamilySessionAccess),
		familyWebSessions:           make(map[string]memoryFamilyWebSessionAccess),
		patientHouseholds:           make(map[string]string),
		householdNames:              make(map[string]string),
		patientNames:                make(map[string]string),
		householdTelegramRecipients: make(map[string][]string),
	}
}

func (m *Memory) PatientIDs(_ context.Context) ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]string, 0, len(m.patientHouseholds))
	for patientID := range m.patientHouseholds {
		result = append(result, patientID)
	}
	sort.Strings(result)
	return result, nil
}

func (m *Memory) ProcessMeasurement(
	_ context.Context,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.processMeasurementLocked(value, recipients, planner)
}

func (m *Memory) ProcessDeviceMeasurement(
	_ context.Context,
	expected DeviceAccess,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	device, ok := m.devices[expected.ID]
	if !ok || device.RevokedAt != nil || device.PatientID != value.PatientID {
		return false, ErrNotFound
	}
	if device.PatientID != expected.PatientID || !device.DeviceAccess.Matches(DeviceBinding{
		DeviceID: expected.ID, BackendBindingID: expected.BackendBindingID,
		CredentialID: expected.CredentialID, CredentialRevision: expected.CredentialRevision,
	}) {
		return false, ErrCredentialConflict
	}
	duplicate, err := m.processMeasurementLocked(value, recipients, planner)
	if err != nil {
		return false, err
	}
	acceptedAt := value.ReceivedAt.UTC()
	if device.LastSeenAt == nil || acceptedAt.After(*device.LastSeenAt) {
		device.LastSeenAt = timePointer(acceptedAt)
	}
	m.devices[expected.ID] = device
	return duplicate, nil
}

func (m *Memory) processMeasurementLocked(
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
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
	startedAt, monitoringActive := m.monitoringStarted[value.PatientID]
	activatesMonitoring := !monitoringActive && value.Quality == domain.QualityValid
	if activatesMonitoring {
		startedAt = value.ReceivedAt.UTC()
	}
	state := m.alertStateLocked(value.PatientID)
	state.MonitoringStartedAt = startedAt
	var changes []alertpolicy.Change
	if monitoringActive || activatesMonitoring {
		changes = planner(state, value)
	}
	if err := validateAlertChanges(value.PatientID, state.OpenAlerts, changes); err != nil {
		return false, err
	}

	m.measurements[value.EventID] = value
	m.measurementBySeq[sequenceKey] = value.EventID
	m.byPatient[value.PatientID] = append(m.byPatient[value.PatientID], value.EventID)
	if activatesMonitoring {
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
	startedAt, monitoringActive := m.monitoringStarted[patientID]
	if !monitoringActive || startedAt.IsZero() {
		return nil
	}
	state := m.alertStateLocked(patientID)
	state.MonitoringStartedAt = startedAt
	changes := planner(state, patientID, at)
	if err := validateAlertChanges(patientID, state.OpenAlerts, changes); err != nil {
		return err
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

func (m *Memory) AcknowledgeAlertForHousehold(_ context.Context, householdID, alertID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	alert, ok := m.alerts[alertID]
	if !ok || m.patientHouseholds[alert.PatientID] != householdID {
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
			PatientDisplayName: deliveryPatientDisplayName(m.patientNames[m.alerts[delivery.AlertID].PatientID]),
			Recipient:          delivery.Recipient,
			Attempts:           delivery.Attempts,
		})
	}
	return result, nil
}

func deliveryPatientDisplayName(value string) string {
	value = domain.NormalizePatientDisplayName(value)
	if value == "" {
		return "Пациент"
	}
	return value
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
