package domain

import (
	"encoding/json"
	"math"
	"strings"
	"testing"
	"time"
)

func TestMeasurementValidateAcceptsDeterministicContentEventID(t *testing.T) {
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	value := validDomainMeasurement(now)
	value.EventID = strings.Repeat("a", 64)

	if err := value.Validate(now); err != nil {
		t.Fatalf("deterministic Android event id must be accepted: %v", err)
	}
}

func TestMeasurementValidateRejectsSequenceOutsidePostgresBigint(t *testing.T) {
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	value := validDomainMeasurement(now)
	value.Sequence = uint64(math.MaxInt64) + 1

	if err := value.Validate(now); err == nil {
		t.Fatal("sequence outside PostgreSQL BIGINT must be rejected before storage")
	}
}

func TestMeasurementValidateRejectsMalformedContentEventID(t *testing.T) {
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	for _, eventID := range []string{
		strings.Repeat("a", 63),
		strings.Repeat("A", 64),
		strings.Repeat("g", 64),
	} {
		value := validDomainMeasurement(now)
		value.EventID = eventID
		if err := value.Validate(now); err == nil {
			t.Fatalf("malformed content event id %q must be rejected", eventID)
		}
	}
}

func TestMeasurementValidateRejectsWhitespaceAroundUUID(t *testing.T) {
	now := time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC)
	value := validDomainMeasurement(now)
	value.EventID = " " + value.EventID + " "
	if err := value.Validate(now); err == nil {
		t.Fatal("UUID whitespace accepted before exact PostgreSQL storage")
	}
}

func TestMeasurementJSONAlwaysContainsZeroSequence(t *testing.T) {
	value := validDomainMeasurement(time.Date(2026, 8, 1, 12, 0, 0, 0, time.UTC))
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(encoded), `"sequence":0`) {
		t.Fatalf("zero sequence disappeared from API response: %s", encoded)
	}
}

func validDomainMeasurement(at time.Time) Measurement {
	return Measurement{
		EventID:  "00000000-0000-4000-8000-000000000010",
		SensorID: "sensor-1", SensorFamily: SensorSibionicsGS1,
		SensorTime: at, PhoneTime: at, GlucoseMgDL: 110,
		TrendMgDLPerMinute: -0.2, Quality: QualityValid,
	}
}
