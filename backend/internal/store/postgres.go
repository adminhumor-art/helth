package store

import (
	"context"
	_ "embed"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	alertpolicy "glucose-monitor/backend/internal/alerts"
	"glucose-monitor/backend/internal/domain"
)

//go:embed schema/initial.sql
var initialSchema string

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

func (p *Postgres) InitializeSchema(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, initialSchema)
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

func (p *Postgres) PrimePatient(ctx context.Context, patientID string, at time.Time) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := ensureMonitoringState(ctx, tx, patientID, at); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (p *Postgres) ProcessMeasurement(
	ctx context.Context,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	startedAt, err := ensureMonitoringState(ctx, tx, value.PatientID, value.ReceivedAt)
	if err != nil {
		return false, err
	}
	state, err := loadAlertState(ctx, tx, value.PatientID, startedAt)
	if err != nil {
		return false, err
	}
	tag, err := tx.Exec(ctx, `
		INSERT INTO measurements (
			event_id, patient_id, sensor_id, sensor_family, sensor_time, phone_time,
			received_at, glucose_mg_dl, trend_mg_dl_per_minute, quality, sequence
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
		ON CONFLICT DO NOTHING`,
		value.EventID, value.PatientID, value.SensorID, value.SensorFamily,
		value.SensorTime, value.PhoneTime, value.ReceivedAt, value.GlucoseMgDL,
		value.TrendMgDLPerMinute, value.Quality, value.Sequence,
	)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() == 0 {
		existing, err := scanMeasurement(tx.QueryRow(ctx, `
			SELECT event_id, patient_id, sensor_id, sensor_family, sensor_time, phone_time,
			       received_at, glucose_mg_dl, trend_mg_dl_per_minute, quality, sequence
			FROM measurements WHERE event_id=$1`, value.EventID))
		if err == nil {
			if !sameMeasurementPayload(existing, value) {
				return false, ErrEventConflict
			}
			if err := tx.Commit(ctx); err != nil {
				return false, err
			}
			return true, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return false, err
		}
		var conflictingEventID string
		err = tx.QueryRow(ctx, `
			SELECT event_id FROM measurements
			WHERE patient_id=$1 AND sensor_id=$2 AND sequence=$3`,
			value.PatientID, value.SensorID, value.Sequence,
		).Scan(&conflictingEventID)
		if err == nil {
			return false, ErrEventConflict
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return false, err
		}
		return false, ErrEventConflict
	}
	changes := planner(state, value)
	if err := validateAlertChanges(value.PatientID, state.OpenAlerts, changes); err != nil {
		return false, err
	}
	if err := applyAlertChanges(ctx, tx, changes, recipients); err != nil {
		return false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return false, err
	}
	return false, nil
}

func (p *Postgres) ProcessStaleness(
	ctx context.Context,
	patientID string,
	at time.Time,
	recipients []string,
	planner StalenessAlertPlanner,
) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	startedAt, err := ensureMonitoringState(ctx, tx, patientID, at)
	if err != nil {
		return err
	}
	state, err := loadAlertState(ctx, tx, patientID, startedAt)
	if err != nil {
		return err
	}
	changes := planner(state, patientID, at)
	if err := validateAlertChanges(patientID, state.OpenAlerts, changes); err != nil {
		return err
	}
	if err := applyAlertChanges(ctx, tx, changes, recipients); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func ensureMonitoringState(ctx context.Context, tx pgx.Tx, patientID string, at time.Time) (time.Time, error) {
	var lockedPatientID string
	if err := tx.QueryRow(ctx, `SELECT id FROM patients WHERE id=$1 FOR UPDATE`, patientID).Scan(&lockedPatientID); errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, ErrNotFound
	} else if err != nil {
		return time.Time{}, err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO patient_monitoring_state (patient_id, started_at)
		VALUES ($1,$2) ON CONFLICT (patient_id) DO NOTHING`, patientID, at.UTC()); err != nil {
		return time.Time{}, err
	}
	var startedAt time.Time
	if err := tx.QueryRow(ctx, `
		SELECT started_at FROM patient_monitoring_state WHERE patient_id=$1`, patientID).Scan(&startedAt); err != nil {
		return time.Time{}, err
	}
	return startedAt, nil
}

func loadAlertState(ctx context.Context, tx pgx.Tx, patientID string, startedAt time.Time) (alertpolicy.State, error) {
	state := alertpolicy.State{MonitoringStartedAt: startedAt}
	var phoneTime, receivedAt time.Time
	err := tx.QueryRow(ctx, `
		SELECT sensor_time, phone_time, received_at FROM measurements
		WHERE patient_id=$1 AND quality='valid'
		ORDER BY sensor_time DESC LIMIT 1`, patientID).Scan(&state.LatestAt, &phoneTime, &receivedAt)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return alertpolicy.State{}, err
	}
	if err == nil {
		state.LatestFreshAt = domain.Measurement{
			SensorTime: state.LatestAt,
			PhoneTime:  phoneTime,
			ReceivedAt: receivedAt,
		}.FreshnessTime()
	}
	rows, err := tx.Query(ctx, `
		SELECT id, patient_id, kind, opened_at, closed_at, acknowledged_at,
		       COALESCE(measurement_id, ''), glucose_mg_dl
		FROM alerts WHERE patient_id=$1 AND closed_at IS NULL
		ORDER BY opened_at ASC, id ASC`, patientID)
	if err != nil {
		return alertpolicy.State{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var alert domain.Alert
		if err := rows.Scan(
			&alert.ID, &alert.PatientID, &alert.Kind, &alert.OpenedAt,
			&alert.ClosedAt, &alert.AcknowledgedAt, &alert.MeasurementID,
			&alert.GlucoseMgDL,
		); err != nil {
			return alertpolicy.State{}, err
		}
		state.OpenAlerts = append(state.OpenAlerts, alert)
	}
	if err := rows.Err(); err != nil {
		return alertpolicy.State{}, err
	}
	return state, nil
}

func applyAlertChanges(ctx context.Context, tx pgx.Tx, changes []alertpolicy.Change, recipients []string) error {
	for _, change := range changes {
		alert := change.Alert
		switch change.Type {
		case alertpolicy.Opened:
			if _, err := tx.Exec(ctx, `
				INSERT INTO alerts (
					id, patient_id, kind, opened_at, closed_at, acknowledged_at,
					measurement_id, glucose_mg_dl
				) VALUES ($1,$2,$3,$4,NULL,$5,NULLIF($6,''),$7)`,
				alert.ID, alert.PatientID, alert.Kind, alert.OpenedAt,
				alert.AcknowledgedAt, alert.MeasurementID, alert.GlucoseMgDL,
			); err != nil {
				return err
			}
			for _, recipient := range recipients {
				recipient = strings.TrimSpace(recipient)
				if recipient == "" {
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
		case alertpolicy.Closed:
			tag, err := tx.Exec(ctx, `
				UPDATE alerts
				SET closed_at=$2, acknowledged_at=COALESCE(acknowledged_at,$3)
				WHERE id=$1 AND patient_id=$4 AND kind=$5 AND closed_at IS NULL`,
				alert.ID, alert.ClosedAt, alert.AcknowledgedAt, alert.PatientID, alert.Kind)
			if err != nil {
				return err
			}
			if tag.RowsAffected() != 1 {
				return fmt.Errorf("%w: open alert changed concurrently", ErrInvalidAlertPlan)
			}
		default:
			return fmt.Errorf("%w: unknown change type %q", ErrInvalidAlertPlan, change.Type)
		}
	}
	return nil
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

func (p *Postgres) PatientSnapshot(ctx context.Context, patientID string) (domain.PatientSnapshot, error) {
	tx, err := p.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return domain.PatientSnapshot{}, err
	}
	defer tx.Rollback(ctx)
	snapshot := domain.PatientSnapshot{PatientID: patientID, OpenAlerts: make([]domain.Alert, 0)}
	latest, err := scanMeasurement(tx.QueryRow(ctx, `
		SELECT event_id, patient_id, sensor_id, sensor_family, sensor_time, phone_time,
		       received_at, glucose_mg_dl, trend_mg_dl_per_minute, quality, sequence
		FROM measurements WHERE patient_id=$1 AND quality='valid'
		ORDER BY sensor_time DESC LIMIT 1`, patientID))
	if err == nil {
		snapshot.Latest = &latest
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return domain.PatientSnapshot{}, err
	}
	rows, err := tx.Query(ctx, `
		SELECT id, patient_id, kind, opened_at, closed_at, acknowledged_at,
		       COALESCE(measurement_id, ''), glucose_mg_dl
		FROM alerts
		WHERE patient_id=$1 AND closed_at IS NULL
		ORDER BY opened_at ASC, id ASC`, patientID)
	if err != nil {
		return domain.PatientSnapshot{}, err
	}
	for rows.Next() {
		var alert domain.Alert
		if err := rows.Scan(
			&alert.ID, &alert.PatientID, &alert.Kind, &alert.OpenedAt,
			&alert.ClosedAt, &alert.AcknowledgedAt, &alert.MeasurementID,
			&alert.GlucoseMgDL,
		); err != nil {
			rows.Close()
			return domain.PatientSnapshot{}, err
		}
		snapshot.OpenAlerts = append(snapshot.OpenAlerts, alert)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return domain.PatientSnapshot{}, err
	}
	rows.Close()
	if err := tx.Commit(ctx); err != nil {
		return domain.PatientSnapshot{}, err
	}
	return snapshot, nil
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

func (p *Postgres) OpenAlerts(ctx context.Context, patientID string) ([]domain.Alert, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT id, patient_id, kind, opened_at, closed_at, acknowledged_at,
		       COALESCE(measurement_id, ''), glucose_mg_dl
		FROM alerts
		WHERE patient_id=$1 AND closed_at IS NULL
		ORDER BY opened_at ASC, id ASC`, patientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]domain.Alert, 0)
	for rows.Next() {
		var alert domain.Alert
		if err := rows.Scan(
			&alert.ID, &alert.PatientID, &alert.Kind, &alert.OpenedAt,
			&alert.ClosedAt, &alert.AcknowledgedAt, &alert.MeasurementID,
			&alert.GlucoseMgDL,
		); err != nil {
			return nil, err
		}
		result = append(result, alert)
	}
	return result, rows.Err()
}

func (p *Postgres) AcknowledgeAlert(ctx context.Context, patientID, alertID string, at time.Time) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alerts SET acknowledged_at=COALESCE(acknowledged_at, $3)
		WHERE id=$1 AND patient_id=$2`, alertID, patientID, at)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) ClaimDueAlertDeliveries(
	ctx context.Context,
	at time.Time,
	limit int,
	leaseToken string,
	leaseExpiresAt time.Time,
) ([]domain.AlertDelivery, error) {
	if limit <= 0 || strings.TrimSpace(leaseToken) == "" || !leaseExpiresAt.After(at) {
		return nil, errors.New("invalid alert delivery lease")
	}
	rows, err := p.pool.Query(ctx, `
		WITH candidates AS MATERIALIZED (
			SELECT id
			FROM alert_deliveries
			WHERE status='pending' AND next_attempt_at <= $1
			  AND (lease_expires_at IS NULL OR lease_expires_at <= $1)
			ORDER BY next_attempt_at ASC, id ASC
			FOR UPDATE SKIP LOCKED
			LIMIT $2
		)
		UPDATE alert_deliveries d
		SET lease_token=$3, lease_expires_at=$4
		FROM candidates c, alerts a
		WHERE d.id=c.id AND a.id=d.alert_id
		RETURNING d.id, d.recipient, d.attempts,
		          a.id, a.patient_id, a.kind, a.opened_at, a.closed_at,
		          a.acknowledged_at, COALESCE(a.measurement_id::text, ''), a.glucose_mg_dl`,
		at, limit, leaseToken, leaseExpiresAt)
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

func (p *Postgres) MarkAlertDeliverySent(ctx context.Context, id, leaseToken string, at time.Time) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alert_deliveries
		SET status='sent', attempts=attempts+1, sent_at=$3, last_error=NULL,
		    lease_token=NULL, lease_expires_at=NULL
		WHERE id=$1 AND status='pending' AND lease_token=$2
		  AND lease_expires_at > $3`, id, leaseToken, at)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) MarkAlertDeliveryFailed(ctx context.Context, id, leaseToken string, at, next time.Time, lastError string) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alert_deliveries
		SET attempts=attempts+1, next_attempt_at=$4, last_error=$5,
		    lease_token=NULL, lease_expires_at=NULL
		WHERE id=$1 AND status='pending' AND lease_token=$2
		  AND lease_expires_at > $3`, id, leaseToken, at, next, lastError)
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
