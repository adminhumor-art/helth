package store

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"glucose-monitor/backend/internal/domain"
)

var ErrNotFound = errors.New("not found")

type Store interface {
	Ingest(context.Context, domain.Measurement) (duplicate bool, err error)
	Latest(context.Context, string) (*domain.Measurement, error)
	List(context.Context, string, time.Time, time.Time) ([]domain.Measurement, error)
	SaveAlert(context.Context, domain.Alert, []string) error
	AcknowledgeAlert(context.Context, string, time.Time) error
	DueAlertDeliveries(context.Context, time.Time, int) ([]domain.AlertDelivery, error)
	MarkAlertDeliverySent(context.Context, string, time.Time) error
	MarkAlertDeliveryFailed(context.Context, string, time.Time, string) error
}

type Memory struct {
	mu           sync.RWMutex
	measurements map[string]domain.Measurement
	byPatient    map[string][]string
	alerts       map[string]domain.Alert
	deliveries   map[string]memoryDelivery
}

type memoryDelivery struct {
	ID            string
	AlertID       string
	Recipient     string
	Status        string
	Attempts      int
	NextAttemptAt time.Time
}

func NewMemory() *Memory {
	return &Memory{
		measurements: make(map[string]domain.Measurement),
		byPatient:    make(map[string][]string),
		alerts:       make(map[string]domain.Alert),
		deliveries:   make(map[string]memoryDelivery),
	}
}

func (m *Memory) Ingest(_ context.Context, value domain.Measurement) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.measurements[value.EventID]; ok {
		return true, nil
	}
	m.measurements[value.EventID] = value
	m.byPatient[value.PatientID] = append(m.byPatient[value.PatientID], value.EventID)
	return false, nil
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

func (m *Memory) SaveAlert(_ context.Context, alert domain.Alert, recipients []string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.alerts[alert.ID] = alert
	if alert.ClosedAt == nil {
		for _, recipient := range recipients {
			id := deliveryID(alert.ID, recipient)
			if _, exists := m.deliveries[id]; !exists {
				m.deliveries[id] = memoryDelivery{
					ID: id, AlertID: alert.ID, Recipient: recipient,
					Status: "pending", NextAttemptAt: alert.OpenedAt,
				}
			}
		}
	}
	return nil
}

func (m *Memory) AcknowledgeAlert(_ context.Context, alertID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	alert, ok := m.alerts[alertID]
	if !ok {
		return ErrNotFound
	}
	if alert.AcknowledgedAt == nil {
		alert.AcknowledgedAt = &at
		m.alerts[alertID] = alert
	}
	return nil
}

func (m *Memory) DueAlertDeliveries(_ context.Context, at time.Time, limit int) ([]domain.AlertDelivery, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.AlertDelivery, 0, limit)
	for _, delivery := range m.deliveries {
		if delivery.Status != "pending" || delivery.NextAttemptAt.After(at) {
			continue
		}
		result = append(result, domain.AlertDelivery{
			ID: delivery.ID, Alert: m.alerts[delivery.AlertID],
			Recipient: delivery.Recipient, Attempts: delivery.Attempts,
		})
		if len(result) == limit {
			break
		}
	}
	return result, nil
}

func (m *Memory) MarkAlertDeliverySent(_ context.Context, id string, _ time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delivery, ok := m.deliveries[id]
	if !ok {
		return ErrNotFound
	}
	delivery.Status = "sent"
	delivery.Attempts++
	m.deliveries[id] = delivery
	return nil
}

func (m *Memory) MarkAlertDeliveryFailed(_ context.Context, id string, next time.Time, _ string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delivery, ok := m.deliveries[id]
	if !ok {
		return ErrNotFound
	}
	delivery.Attempts++
	delivery.NextAttemptAt = next
	m.deliveries[id] = delivery
	return nil
}

func deliveryID(alertID, recipient string) string {
	sum := sha256.Sum256([]byte(alertID + "\x00telegram\x00" + recipient))
	sum[6] = (sum[6] & 0x0f) | 0x50
	sum[8] = (sum[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", sum[0:4], sum[4:6], sum[6:8], sum[8:10], sum[10:16])
}
