package store

import (
	"context"
	_ "embed"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"glucose-monitor/backend/internal/domain"
)

//go:embed migrations/001_init.sql
var initialMigration string

type Postgres struct {
	pool *pgxpool.Pool
}

func NewPostgres(ctx context.Context, databaseURL string) (*Postgres, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return &Postgres{pool: pool}, nil
}

func (p *Postgres) Close() {
	p.pool.Close()
}

func (p *Postgres) Migrate(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, initialMigration)
	return err
}

func (p *Postgres) Bootstrap(ctx context.Context, patientID string) error {
	householdID := "00000000-0000-4000-8000-000000000100"
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `
		INSERT INTO households (id, name) VALUES ($1, 'Семья')
		ON CONFLICT (id) DO NOTHING`, householdID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO patients (id, household_id, display_name)
		VALUES ($1, $2, 'Пациент') ON CONFLICT (id) DO NOTHING`, patientID, householdID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (p *Postgres) Ingest(ctx context.Context, value domain.Measurement) (bool, error) {
	tag, err := p.pool.Exec(ctx, `
		INSERT INTO measurements (
			event_id, patient_id, sensor_id, sensor_family, sensor_time, phone_time,
			received_at, glucose_mg_dl, trend_mg_dl_per_minute, quality, sequence
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
		ON CONFLICT (event_id) DO NOTHING`,
		value.EventID, value.PatientID, value.SensorID, value.SensorFamily,
		value.SensorTime, value.PhoneTime, value.ReceivedAt, value.GlucoseMgDL,
		value.TrendMgDLPerMinute, value.Quality, value.Sequence,
	)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 0, nil
}

func (p *Postgres) Latest(ctx context.Context, patientID string) (*domain.Measurement, error) {
	row := p.pool.QueryRow(ctx, `
		SELECT event_id, patient_id, sensor_id, sensor_family, sensor_time, phone_time,
		       received_at, glucose_mg_dl, trend_mg_dl_per_minute, quality, sequence
		FROM measurements WHERE patient_id=$1
		ORDER BY sensor_time DESC LIMIT 1`, patientID)
	value, err := scanMeasurement(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &value, nil
}

func (p *Postgres) List(ctx context.Context, patientID string, from, to time.Time) ([]domain.Measurement, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT event_id, patient_id, sensor_id, sensor_family, sensor_time, phone_time,
		       received_at, glucose_mg_dl, trend_mg_dl_per_minute, quality, sequence
		FROM measurements
		WHERE patient_id=$1 AND sensor_time >= $2 AND sensor_time <= $3
		ORDER BY sensor_time ASC`, patientID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.Measurement, 0)
	for rows.Next() {
		value, err := scanMeasurement(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, value)
	}
	return result, rows.Err()
}

func (p *Postgres) SaveAlert(ctx context.Context, alert domain.Alert, recipients []string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `
		INSERT INTO alerts (
			id, patient_id, kind, opened_at, closed_at, acknowledged_at,
			measurement_id, glucose_mg_dl
		) VALUES ($1,$2,$3,$4,$5,$6,NULLIF($7,''),$8)
		ON CONFLICT (id) DO UPDATE SET
			closed_at=EXCLUDED.closed_at,
			acknowledged_at=COALESCE(alerts.acknowledged_at, EXCLUDED.acknowledged_at)`,
		alert.ID, alert.PatientID, alert.Kind, alert.OpenedAt, alert.ClosedAt,
		alert.AcknowledgedAt, alert.MeasurementID, alert.GlucoseMgDL,
	); err != nil {
		return err
	}
	if alert.ClosedAt == nil {
		for _, recipient := range recipients {
			if recipient = strings.TrimSpace(recipient); recipient == "" {
				continue
			}
			if _, err := tx.Exec(ctx, `
				INSERT INTO alert_deliveries (
					id, alert_id, channel, recipient, status, next_attempt_at
				) VALUES ($1,$2,'telegram',$3,'pending',$4)
				ON CONFLICT (alert_id, channel, recipient) DO NOTHING`,
				deliveryID(alert.ID, recipient), alert.ID, recipient, alert.OpenedAt,
			); err != nil {
				return err
			}
		}
	}
	return tx.Commit(ctx)
}

func (p *Postgres) AcknowledgeAlert(ctx context.Context, alertID string, at time.Time) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alerts SET acknowledged_at=COALESCE(acknowledged_at, $2) WHERE id=$1`, alertID, at)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) DueAlertDeliveries(ctx context.Context, at time.Time, limit int) ([]domain.AlertDelivery, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT d.id, d.recipient, d.attempts,
		       a.id, a.patient_id, a.kind, a.opened_at, a.closed_at,
		       a.acknowledged_at, COALESCE(a.measurement_id::text, ''), a.glucose_mg_dl
		FROM alert_deliveries d
		JOIN alerts a ON a.id=d.alert_id
		WHERE d.status='pending' AND d.next_attempt_at <= $1
		ORDER BY d.next_attempt_at ASC
		LIMIT $2`, at, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.AlertDelivery, 0, limit)
	for rows.Next() {
		var value domain.AlertDelivery
		if err := rows.Scan(
			&value.ID, &value.Recipient, &value.Attempts,
			&value.Alert.ID, &value.Alert.PatientID, &value.Alert.Kind,
			&value.Alert.OpenedAt, &value.Alert.ClosedAt, &value.Alert.AcknowledgedAt,
			&value.Alert.MeasurementID, &value.Alert.GlucoseMgDL,
		); err != nil {
			return nil, err
		}
		result = append(result, value)
	}
	return result, rows.Err()
}

func (p *Postgres) MarkAlertDeliverySent(ctx context.Context, id string, at time.Time) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alert_deliveries
		SET status='sent', attempts=attempts+1, sent_at=$2, last_error=NULL
		WHERE id=$1 AND status='pending'`, id, at)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) MarkAlertDeliveryFailed(ctx context.Context, id string, next time.Time, lastError string) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alert_deliveries
		SET attempts=attempts+1, next_attempt_at=$2, last_error=$3
		WHERE id=$1 AND status='pending'`, id, next, lastError)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

type scanner interface {
	Scan(...any) error
}

func scanMeasurement(row scanner) (domain.Measurement, error) {
	var value domain.Measurement
	err := row.Scan(
		&value.EventID, &value.PatientID, &value.SensorID, &value.SensorFamily,
		&value.SensorTime, &value.PhoneTime, &value.ReceivedAt, &value.GlucoseMgDL,
		&value.TrendMgDLPerMinute, &value.Quality, &value.Sequence,
	)
	return value, err
}
