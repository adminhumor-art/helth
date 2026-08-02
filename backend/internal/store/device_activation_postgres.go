package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

func (p *Postgres) ProvisionDeviceActivation(ctx context.Context, value DeviceActivationProvisioning) error {
	value = normalizeDeviceActivationProvisioning(value)
	if err := validateDeviceActivationProvisioning(value); err != nil {
		return err
	}
	identity := value.Identity
	activation := value.Activation
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(736523129)`); err != nil {
		return err
	}

	var conflict bool
	if err := tx.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM devices
			WHERE token_hash IN ($2,$3,$4) OR (
				id<>$5 AND (
					backend_binding_id=$7 OR
					(credential_id=$8 AND credential_revision=$9)
				)
			)
			UNION ALL
			SELECT 1 FROM family_sessions
			WHERE token_hash IN ($3,$4) OR (token_hash=$2 AND id<>$6)
			UNION ALL
			SELECT 1 FROM family_web_sessions
			WHERE token_hash IN ($3,$4) OR csrf_token_hash IN ($3,$4)
			UNION ALL
			SELECT 1 FROM device_activation_codes
			WHERE (code_hash=$3 AND id<>$1)
			   OR (device_nonce_hash=$4 AND device_id<>$5)
			   OR (device_id=$5 AND consumed_at IS NULL AND expires_at>$10 AND id<>$1)
		)`, activation.ID, identity.FamilyTokenHash, activation.CodeHash, activation.DeviceNonceHash,
		identity.DeviceID, identity.FamilySessionID, identity.BackendBindingID,
		identity.CredentialID, identity.CredentialRevision, activation.CreatedAt,
	).Scan(&conflict); err != nil {
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
		VALUES ($1, $2, $3, NULL, $4, $5, $6)
		ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
		WHERE devices.patient_id=EXCLUDED.patient_id
		  AND devices.name=EXCLUDED.name
		  AND devices.token_hash IS NULL
		  AND devices.last_seen_at IS NULL
		  AND devices.revoked_at IS NULL
		  AND devices.backend_binding_id=EXCLUDED.backend_binding_id
		  AND devices.credential_id=EXCLUDED.credential_id
		  AND devices.credential_revision=EXCLUDED.credential_revision
		RETURNING id`, identity.DeviceID, identity.PatientID, identity.DeviceName,
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
		RETURNING id`, identity.FamilySessionID, identity.HouseholdID,
		identity.FamilyTokenHash, identity.FamilySessionExpiresAt).Scan(&familySessionID); err != nil {
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
	var activationID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO device_activation_codes (
			id, device_id, code_hash, device_nonce_hash, created_at, expires_at
		) VALUES ($1,$2,$3,$4,$5,$6)
		ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
		WHERE device_activation_codes.device_id=EXCLUDED.device_id
		  AND device_activation_codes.code_hash=EXCLUDED.code_hash
		  AND device_activation_codes.device_nonce_hash=EXCLUDED.device_nonce_hash
		  AND device_activation_codes.created_at=EXCLUDED.created_at
		  AND device_activation_codes.expires_at=EXCLUDED.expires_at
		  AND device_activation_codes.consumed_at IS NULL
		RETURNING id`, activation.ID, identity.DeviceID, activation.CodeHash,
		activation.DeviceNonceHash, activation.CreatedAt, activation.ExpiresAt).Scan(&activationID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrCredentialConflict
		}
		return err
	}
	return tx.Commit(ctx)
}

func (p *Postgres) ConsumeDeviceActivation(
	ctx context.Context,
	value DeviceActivationConsume,
) (DeviceAccess, error) {
	value.DeviceID = canonicalUUID(value.DeviceID)
	value.At = value.At.UTC()
	if err := validateDeviceActivationConsume(value); err != nil {
		return DeviceAccess{}, err
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return DeviceAccess{}, err
	}
	defer tx.Rollback(ctx)

	var result DeviceAccess
	var activationID string
	err = tx.QueryRow(ctx, `
		SELECT device.id, device.patient_id, device.backend_binding_id,
		       device.credential_id, device.credential_revision, activation.id
		FROM device_activation_codes AS activation
		JOIN devices AS device ON device.id=activation.device_id
		WHERE activation.code_hash=$1
		  AND activation.device_id=$2
		  AND activation.device_nonce_hash=$3
		  AND activation.consumed_at IS NULL
		  AND activation.created_at<=$4
		  AND activation.expires_at>$4
		  AND device.token_hash IS NULL
		  AND device.revoked_at IS NULL
		FOR UPDATE OF activation, device`,
		value.CodeHash, value.DeviceID, value.DeviceNonceHash, value.At,
	).Scan(&result.ID, &result.PatientID, &result.BackendBindingID,
		&result.CredentialID, &result.CredentialRevision, &activationID)
	if errors.Is(err, pgx.ErrNoRows) {
		return DeviceAccess{}, ErrNotFound
	}
	if err != nil {
		return DeviceAccess{}, err
	}

	var conflict bool
	if err := tx.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM devices WHERE token_hash=$1
			UNION ALL SELECT 1 FROM family_sessions WHERE token_hash=$1
			UNION ALL SELECT 1 FROM family_web_sessions WHERE token_hash=$1 OR csrf_token_hash=$1
			UNION ALL SELECT 1 FROM device_activation_codes WHERE code_hash=$1 OR device_nonce_hash=$1
		)`, value.DeviceTokenHash).Scan(&conflict); err != nil {
		return DeviceAccess{}, err
	}
	if conflict {
		return DeviceAccess{}, ErrCredentialConflict
	}
	deviceTag, err := tx.Exec(ctx, `
		UPDATE devices SET token_hash=$2
		WHERE id=$1 AND token_hash IS NULL AND revoked_at IS NULL`, result.ID, value.DeviceTokenHash)
	if err != nil {
		return DeviceAccess{}, err
	}
	if deviceTag.RowsAffected() != 1 {
		return DeviceAccess{}, ErrNotFound
	}
	activationTag, err := tx.Exec(ctx, `
		UPDATE device_activation_codes SET consumed_at=$2
		WHERE id=$1 AND consumed_at IS NULL`, activationID, value.At)
	if err != nil {
		return DeviceAccess{}, err
	}
	if activationTag.RowsAffected() != 1 {
		return DeviceAccess{}, ErrNotFound
	}
	if err := tx.Commit(ctx); err != nil {
		return DeviceAccess{}, err
	}
	return result, nil
}
