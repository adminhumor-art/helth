package domain

import (
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"strings"
	"time"
)

type SensorFamily string

const (
	SensorSibionicsGS1   SensorFamily = "sibionics_gs1"
	SensorSibionicsGS1Sb SensorFamily = "sibionics_gs1sb"
	SensorSibionicsGS3   SensorFamily = "sibionics_gs3"
	SensorSimulator      SensorFamily = "simulator"
)

type MeasurementQuality string

const (
	QualityValid     MeasurementQuality = "valid"
	QualityWarmingUp MeasurementQuality = "warming_up"
	QualityDegraded  MeasurementQuality = "degraded"
)

type Measurement struct {
	EventID            string             `json:"eventId"`
	PatientID          string             `json:"patientId,omitempty"`
	SensorID           string             `json:"sensorId"`
	SensorFamily       SensorFamily       `json:"sensorFamily"`
	SensorTime         time.Time          `json:"sensorTime"`
	PhoneTime          time.Time          `json:"phoneTime"`
	ReceivedAt         time.Time          `json:"receivedAt,omitempty"`
	GlucoseMgDL        int                `json:"glucoseMgDl"`
	TrendMgDLPerMinute float64            `json:"trendMgDlPerMinute"`
	Quality            MeasurementQuality `json:"quality"`
	Sequence           uint64             `json:"sequence,omitempty"`
}

func (m Measurement) Validate(now time.Time) error {
	if !isUUID(m.EventID) {
		return errors.New("eventId must be a UUID")
	}
	if strings.TrimSpace(m.SensorID) == "" || len(m.SensorID) > 128 {
		return errors.New("sensorId must contain 1..128 characters")
	}
	switch m.SensorFamily {
	case SensorSibionicsGS1, SensorSibionicsGS1Sb, SensorSibionicsGS3:
	case SensorSimulator:
		return errors.New("simulator measurements cannot enter the product ingest path")
	default:
		return fmt.Errorf("unsupported sensorFamily %q", m.SensorFamily)
	}
	if m.SensorTime.IsZero() || m.PhoneTime.IsZero() {
		return errors.New("sensorTime and phoneTime are required")
	}
	if m.SensorTime.After(now.Add(5*time.Minute)) || m.PhoneTime.After(now.Add(5*time.Minute)) {
		return errors.New("measurement time is too far in the future")
	}
	if m.GlucoseMgDL < 20 || m.GlucoseMgDL > 600 {
		return errors.New("glucoseMgDl is outside 20..600")
	}
	if math.IsNaN(m.TrendMgDLPerMinute) || math.IsInf(m.TrendMgDLPerMinute, 0) || math.Abs(m.TrendMgDLPerMinute) > 20 {
		return errors.New("trendMgDlPerMinute is outside -20..20")
	}
	switch m.Quality {
	case QualityValid, QualityWarmingUp, QualityDegraded:
	default:
		return fmt.Errorf("unsupported quality %q", m.Quality)
	}
	return nil
}

func isUUID(value string) bool {
	value = strings.TrimSpace(value)
	if len(value) != 36 || value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' {
		return false
	}
	compact := strings.ReplaceAll(value, "-", "")
	decoded := make([]byte, 16)
	_, err := hex.Decode(decoded, []byte(compact))
	return err == nil
}

type AlertKind string

const (
	AlertLow        AlertKind = "low"
	AlertHigh       AlertKind = "high"
	AlertRapidFall  AlertKind = "rapid_fall"
	AlertRapidRise  AlertKind = "rapid_rise"
	AlertSignalLoss AlertKind = "signal_loss"
)

type Alert struct {
	ID             string     `json:"id"`
	PatientID      string     `json:"patientId"`
	Kind           AlertKind  `json:"kind"`
	OpenedAt       time.Time  `json:"openedAt"`
	ClosedAt       *time.Time `json:"closedAt,omitempty"`
	AcknowledgedAt *time.Time `json:"acknowledgedAt,omitempty"`
	MeasurementID  string     `json:"measurementId,omitempty"`
	GlucoseMgDL    *int       `json:"glucoseMgDl,omitempty"`
}

type AlertDelivery struct {
	ID        string
	Alert     Alert
	Recipient string
	Attempts  int
}

type Freshness string

const (
	FreshnessFresh   Freshness = "fresh"
	FreshnessStale   Freshness = "stale"
	FreshnessMissing Freshness = "missing"
)

type PatientSnapshot struct {
	PatientID  string       `json:"patientId"`
	Freshness  Freshness    `json:"freshness"`
	Latest     *Measurement `json:"latest"`
	OpenAlerts []Alert      `json:"openAlerts"`
}
