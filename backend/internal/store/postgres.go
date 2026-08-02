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

func (p *Postgres) BootstrapAccess(ctx context.Context, identity BootstrapIdentity) error {
	identity = normalizeBootstrapIdentity(identity)
	if err := validateBootstrapIdentity(identity); err != nil {
		return err
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	// Serializes provisioning so one digest cannot be concurrently assigned to
	// both a device and a family session in separate tables.
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(736523129)`); err != nil {
		return err
	}
	var conflict bool
	if err := tx.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM devices
			WHERE token_hash=$2 OR (
				id<>$3 AND (
					token_hash=$1 OR backend_binding_id=$5 OR
					(credential_id=$6 AND credential_revision=$7)
				)
			)
			UNION ALL
			SELECT 1 FROM family_sessions
			WHERE token_hash=$1 OR (token_hash=$2 AND id<>$4)
		)`, identity.DeviceTokenHash, identity.FamilyTokenHash, identity.DeviceID, identity.FamilySessionID,
		identity.BackendBindingID, identity.CredentialID, identity.CredentialRevision).Scan(&conflict); err != nil {
		return err
	}
	if conflict {
		return ErrCredentialConflict
	}
	var householdExists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM households WHERE id=$1)`, identity.HouseholdID).Scan(&householdExists); err != nil {
		return err
	}
	var existingRecipients []string
	rows, err := tx.Query(ctx, `
		SELECT telegram_chat_id FROM family_members
		WHERE household_id=$1 AND telegram_chat_id IS NOT NULL
		ORDER BY telegram_chat_id`, identity.HouseholdID)
	if err != nil {
		return err
	}
	for rows.Next() {
		var recipient string
		if err := rows.Scan(&recipient); err != nil {
			rows.Close()
			return err
		}
		existingRecipients = append(existingRecipients, recipient)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	if householdExists && !sameStrings(existingRecipients, identity.TelegramRecipients) {
		return ErrCredentialConflict
	}
	var householdID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO households (id, name) VALUES ($1, $2)
		ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
		WHERE households.name=EXCLUDED.name
		RETURNING id`, identity.HouseholdID, identity.HouseholdName).Scan(&householdID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrCredentialConflict
		}
		return err
	}
	var patientID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO patients (id, household_id, display_name)
		VALUES ($1, $2, $3)
		ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
		WHERE patients.household_id=EXCLUDED.household_id
		  AND patients.display_name=EXCLUDED.display_name
		RETURNING id`, identity.PatientID, identity.HouseholdID, identity.PatientName).Scan(&patientID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrCredentialConflict
		}
		return err
	}
	var deviceID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO devices (
			id, patient_id, name, token_hash,
			backend_binding_id, credential_id, credential_revision
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
		WHERE devices.patient_id=EXCLUDED.patient_id
		  AND devices.name=EXCLUDED.name
		  AND devices.token_hash=EXCLUDED.token_hash
		  AND devices.backend_binding_id=EXCLUDED.backend_binding_id
		  AND devices.credential_id=EXCLUDED.credential_id
		  AND devices.credential_revision=EXCLUDED.credential_revision
		RETURNING id`, identity.DeviceID, identity.PatientID, identity.DeviceName, identity.DeviceTokenHash,
		identity.BackendBindingID, identity.CredentialID, identity.CredentialRevision).Scan(&deviceID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrCredentialConflict
		}
		return err
	}
	var familySessionID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO family_sessions (id, household_id, token_hash, expires_at)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
		WHERE family_sessions.household_id=EXCLUDED.household_id
		  AND family_sessions.token_hash=EXCLUDED.token_hash
		  AND family_sessions.expires_at IS NOT DISTINCT FROM EXCLUDED.expires_at
		RETURNING id`, identity.FamilySessionID, identity.HouseholdID, identity.FamilyTokenHash, identity.FamilySessionExpiresAt).Scan(&familySessionID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrCredentialConflict
		}
		return err
	}
	for _, recipient := range identity.TelegramRecipients {
		if _, err := tx.Exec(ctx, `
			INSERT INTO family_members (
				id, household_id, email, display_name, role, telegram_chat_id
			) VALUES ($1, $2, $3, 'Telegram', 'relative', $4)
			ON CONFLICT (household_id, telegram_chat_id)
			WHERE telegram_chat_id IS NOT NULL DO NOTHING`,
			bootstrapFamilyMemberID(identity.HouseholdID, recipient), identity.HouseholdID,
			bootstrapFamilyMemberEmail(identity.HouseholdID, recipient), recipient,
		); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (p *Postgres) ResolveActiveDevice(ctx context.Context, tokenHash []byte, _ time.Time) (DeviceAccess, error) {
	if len(tokenHash) != AccessTokenHashSize {
		return DeviceAccess{}, ErrNotFound
	}
	var result DeviceAccess
	err := p.pool.QueryRow(ctx, `
		SELECT id, patient_id, backend_binding_id, credential_id, credential_revision
		FROM devices
		WHERE token_hash=$1 AND revoked_at IS NULL`, tokenHash).Scan(
		&result.ID, &result.PatientID, &result.BackendBindingID,
		&result.CredentialID, &result.CredentialRevision,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return DeviceAccess{}, ErrNotFound
	}
	return result, err
}

func (p *Postgres) TelegramRecipients(ctx context.Context, patientID string) ([]string, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT DISTINCT fm.telegram_chat_id
		FROM patients p
		JOIN family_members fm ON fm.household_id=p.household_id
		WHERE p.id=$1 AND fm.telegram_chat_id IS NOT NULL
		ORDER BY fm.telegram_chat_id`, patientID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]string, 0)
	for rows.Next() {
		var recipient string
		if err := rows.Scan(&recipient); err != nil {
			return nil, err
		}
		result = append(result, recipient)
	}
	return result, rows.Err()
}

func (p *Postgres) HasTelegramRecipients(ctx context.Context) (bool, error) {
	var configured bool
	err := p.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM family_members
			WHERE NULLIF(BTRIM(telegram_chat_id), '') IS NOT NULL
		)`).Scan(&configured)
	return configured, err
}

func (p *Postgres) ValidateProductionAccess(ctx context.Context, at time.Time) error {
	var ready bool
	err := p.pool.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM patients)
		   AND NOT EXISTS (
			SELECT 1 FROM patients p
			WHERE NOT EXISTS (
				SELECT 1 FROM devices d
				WHERE d.patient_id=p.id AND d.revoked_at IS NULL
			) OR NOT EXISTS (
				SELECT 1 FROM family_sessions fs
				WHERE fs.household_id=p.household_id
				  AND fs.revoked_at IS NULL
				  AND (fs.expires_at IS NULL OR fs.expires_at > $1)
			) OR NOT EXISTS (
				SELECT 1 FROM family_members fm
				WHERE fm.household_id=p.household_id
				  AND NULLIF(BTRIM(fm.telegram_chat_id), '') IS NOT NULL
			)
		)`, at.UTC()).Scan(&ready)
	if err != nil {
		return err
	}
	if !ready {
		return ErrAccessNotProvisioned
	}
	return nil
}

func (p *Postgres) ResolveActiveFamilySession(ctx context.Context, tokenHash []byte, at time.Time) (FamilySessionAccess, error) {
	if len(tokenHash) != AccessTokenHashSize {
		return FamilySessionAccess{}, ErrNotFound
	}
	var result FamilySessionAccess
	err := p.pool.QueryRow(ctx, `
		SELECT id, household_id FROM family_sessions
		WHERE token_hash=$1 AND revoked_at IS NULL
		  AND (expires_at IS NULL OR expires_at > $2)`, tokenHash, at.UTC()).Scan(&result.ID, &result.HouseholdID)
	if errors.Is(err, pgx.ErrNoRows) {
		return FamilySessionAccess{}, ErrNotFound
	}
	return result, err
}

func (p *Postgres) HouseholdCanAccessPatient(ctx context.Context, householdID, patientID string) (bool, error) {
	var allowed bool
	err := p.pool.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM patients WHERE id=$1 AND household_id=$2)`, patientID, householdID).Scan(&allowed)
	return allowed, err
}

func (p *Postgres) RevokeDevice(ctx context.Context, deviceID string, at time.Time) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE devices SET revoked_at=COALESCE(revoked_at, $2) WHERE id=$1`, deviceID, at.UTC())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) PatientIDs(ctx context.Context) ([]string, error) {
	rows, err := p.pool.Query(ctx, `SELECT id FROM patients ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]string, 0)
	for rows.Next() {
		var patientID string
		if err := rows.Scan(&patientID); err != nil {
			return nil, err
		}
		result = append(result, patientID)
	}
	return result, rows.Err()
}

func (p *Postgres) ProcessMeasurement(
	ctx context.Context,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	return p.processMeasurement(ctx, nil, value, recipients, planner)
}

func (p *Postgres) ProcessDeviceMeasurement(
	ctx context.Context,
	expected DeviceAccess,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	return p.processMeasurement(ctx, &expected, value, recipients, planner)
}

func (p *Postgres) processMeasurement(
	ctx context.Context,
	expected *DeviceAccess,
	value domain.Measurement,
	recipients []string,
	planner MeasurementAlertPlanner,
) (bool, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	if err := lockPatient(ctx, tx, value.PatientID); err != nil {
		return false, err
	}
	if expected != nil {
		var current DeviceAccess
		err := tx.QueryRow(ctx, `
			SELECT id, patient_id, backend_binding_id, credential_id, credential_revision
			FROM devices
			WHERE id=$1 AND patient_id=$2 AND revoked_at IS NULL
			FOR UPDATE`, expected.ID, value.PatientID).Scan(
			&current.ID, &current.PatientID, &current.BackendBindingID,
			&current.CredentialID, &current.CredentialRevision,
		)
		if errors.Is(err, pgx.ErrNoRows) {
			return false, ErrNotFound
		}
		if err != nil {
			return false, err
		}
		if current.PatientID != expected.PatientID || !current.Matches(DeviceBinding{
			DeviceID: expected.ID, BackendBindingID: expected.BackendBindingID,
			CredentialID: expected.CredentialID, CredentialRevision: expected.CredentialRevision,
		}) {
			return false, ErrCredentialConflict
		}
	}
	startedAt, monitoringActive, err := loadMonitoringStart(ctx, tx, value.PatientID)
	if err != nil {
		return false, err
	}
	activatesMonitoring := !monitoringActive && value.Quality == domain.QualityValid
	if activatesMonitoring {
		startedAt = value.ReceivedAt.UTC()
		if _, err := tx.Exec(ctx, `
			INSERT INTO patient_monitoring_state (patient_id, started_at)
			VALUES ($1,$2)`, value.PatientID, startedAt); err != nil {
			return false, err
		}
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
			if err := markDeviceLastSeen(ctx, tx, expected, value.ReceivedAt); err != nil {
				return false, err
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
	var changes []alertpolicy.Change
	if monitoringActive || activatesMonitoring {
		changes = planner(state, value)
	}
	if err := validateAlertChanges(value.PatientID, state.OpenAlerts, changes); err != nil {
		return false, err
	}
	if err := applyAlertChanges(ctx, tx, changes, recipients); err != nil {
		return false, err
	}
	if err := markDeviceLastSeen(ctx, tx, expected, value.ReceivedAt); err != nil {
		return false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return false, err
	}
	return false, nil
}

func markDeviceLastSeen(ctx context.Context, tx pgx.Tx, expected *DeviceAccess, at time.Time) error {
	if expected == nil {
		return nil
	}
	tag, err := tx.Exec(ctx, `
		UPDATE devices
		SET last_seen_at=GREATEST(COALESCE(last_seen_at, $2), $2)
		WHERE id=$1 AND revoked_at IS NULL`, expected.ID, at.UTC())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
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
	if err := lockPatient(ctx, tx, patientID); err != nil {
		return err
	}
	startedAt, monitoringActive, err := loadMonitoringStart(ctx, tx, patientID)
	if err != nil {
		return err
	}
	if !monitoringActive {
		return nil
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

func lockPatient(ctx context.Context, tx pgx.Tx, patientID string) error {
	var lockedPatientID string
	err := tx.QueryRow(ctx, `SELECT id FROM patients WHERE id=$1 FOR UPDATE`, patientID).Scan(&lockedPatientID)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

func loadMonitoringStart(ctx context.Context, tx pgx.Tx, patientID string) (time.Time, bool, error) {
	var startedAt time.Time
	err := tx.QueryRow(ctx, `
		SELECT started_at FROM patient_monitoring_state WHERE patient_id=$1`, patientID).Scan(&startedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, false, nil
	}
	if err != nil {
		return time.Time{}, false, err
	}
	return startedAt, true, nil
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

func (p *Postgres) AcknowledgeAlertForHousehold(ctx context.Context, householdID, alertID string, at time.Time) error {
	tag, err := p.pool.Exec(ctx, `
		UPDATE alerts a SET acknowledged_at=COALESCE(a.acknowledged_at, $3)
		FROM patients p
		WHERE a.id=$1 AND p.id=a.patient_id AND p.household_id=$2`, alertID, householdID, at.UTC())
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
		FROM candidates c, alerts a, patients p
		WHERE d.id=c.id AND a.id=d.alert_id AND p.id=a.patient_id
		RETURNING d.id, d.recipient, d.attempts,
		          a.id, a.patient_id, a.kind, a.opened_at, a.closed_at,
		          a.acknowledged_at, COALESCE(a.measurement_id::text, ''), a.glucose_mg_dl,
		          p.display_name`,
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
			&value.Alert.MeasurementID, &value.Alert.GlucoseMgDL, &value.PatientDisplayName,
		); err != nil {
			return nil, err
		}
		value.PatientDisplayName = deliveryPatientDisplayName(value.PatientDisplayName)
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
