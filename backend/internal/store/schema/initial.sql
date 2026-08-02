CREATE TABLE IF NOT EXISTS households (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS patients (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    timezone TEXT NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS patient_monitoring_state (
    patient_id UUID PRIMARY KEY REFERENCES patients(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS family_members (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('owner', 'relative', 'viewer')),
    telegram_chat_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, email)
);

CREATE UNIQUE INDEX IF NOT EXISTS family_members_household_telegram_uniq
    ON family_members (household_id, telegram_chat_id)
    WHERE telegram_chat_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    token_hash BYTEA UNIQUE CHECK (token_hash IS NULL OR octet_length(token_hash) = 32),
    backend_binding_id TEXT NOT NULL UNIQUE CHECK (backend_binding_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    credential_id TEXT NOT NULL CHECK (credential_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    credential_revision BIGINT NOT NULL CHECK (credential_revision BETWEEN 1 AND 9007199254740991),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS devices_credential_revision_uniq
    ON devices (credential_id, credential_revision);

CREATE INDEX IF NOT EXISTS devices_active_token_hash_idx
    ON devices (token_hash) WHERE revoked_at IS NULL AND token_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS device_activation_codes (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    code_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(code_hash) = 32),
    device_nonce_hash BYTEA NOT NULL CHECK (octet_length(device_nonce_hash) = 32),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (expires_at <= created_at + INTERVAL '30 minutes'),
    CHECK (consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at < expires_at))
);

CREATE INDEX IF NOT EXISTS device_activation_codes_active_hash_idx
    ON device_activation_codes (code_hash) WHERE consumed_at IS NULL;

CREATE INDEX IF NOT EXISTS device_activation_codes_device_idx
    ON device_activation_codes (device_id, expires_at DESC);

CREATE TABLE IF NOT EXISTS family_sessions (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS family_sessions_active_token_hash_idx
    ON family_sessions (token_hash) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS family_web_sessions (
    id UUID PRIMARY KEY,
    family_access_id UUID NOT NULL REFERENCES family_sessions(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    csrf_token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(csrf_token_hash) = 32),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS family_web_sessions_active_token_hash_idx
    ON family_web_sessions (token_hash) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS measurements (
    event_id TEXT PRIMARY KEY CHECK (
        event_id ~ '^[0-9a-f]{64}$' OR
        event_id ~ '^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$'
    ),
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    sensor_id TEXT NOT NULL,
    sensor_family TEXT NOT NULL CHECK (sensor_family IN ('sibionics_gs1', 'sibionics_gs1sb', 'sibionics_gs3')),
    sensor_time TIMESTAMPTZ NOT NULL,
    phone_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    glucose_mg_dl SMALLINT NOT NULL CHECK (glucose_mg_dl BETWEEN 20 AND 600),
    trend_mg_dl_per_minute DOUBLE PRECISION NOT NULL,
    quality TEXT NOT NULL CHECK (quality IN ('valid', 'warming_up', 'degraded')),
    sequence BIGINT NOT NULL CHECK (sequence BETWEEN 0 AND 9007199254740991),
    UNIQUE (patient_id, sensor_id, sequence)
);

CREATE INDEX IF NOT EXISTS measurements_patient_sensor_time_idx
    ON measurements (patient_id, sensor_time DESC);

CREATE TABLE IF NOT EXISTS alerts (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('low', 'high', 'rapid_fall', 'rapid_rise', 'signal_loss')),
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    measurement_id TEXT REFERENCES measurements(event_id) ON DELETE SET NULL,
    glucose_mg_dl SMALLINT
);

CREATE INDEX IF NOT EXISTS alerts_patient_open_idx
    ON alerts (patient_id, opened_at DESC) WHERE closed_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS alerts_patient_kind_open_uniq
    ON alerts (patient_id, kind) WHERE closed_at IS NULL;

CREATE TABLE IF NOT EXISTS alert_deliveries (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
    channel TEXT NOT NULL CHECK (channel IN ('telegram')),
    recipient TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'sent', 'failed')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token TEXT,
    lease_expires_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (alert_id, channel, recipient),
    CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL))
);

CREATE INDEX IF NOT EXISTS alert_deliveries_due_idx
    ON alert_deliveries (next_attempt_at, id)
    WHERE status = 'pending';
