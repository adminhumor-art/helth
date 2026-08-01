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

CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    token_hash BYTEA,
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS measurements (
    event_id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    sensor_id TEXT NOT NULL,
    sensor_family TEXT NOT NULL,
    sensor_time TIMESTAMPTZ NOT NULL,
    phone_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    glucose_mg_dl SMALLINT NOT NULL CHECK (glucose_mg_dl BETWEEN 20 AND 600),
    trend_mg_dl_per_minute DOUBLE PRECISION NOT NULL,
    quality TEXT NOT NULL CHECK (quality IN ('valid', 'warming_up', 'degraded')),
    sequence BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS measurements_patient_sensor_time_idx
    ON measurements (patient_id, sensor_time DESC);

CREATE TABLE IF NOT EXISTS alerts (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    measurement_id UUID REFERENCES measurements(event_id) ON DELETE SET NULL,
    glucose_mg_dl SMALLINT
);

CREATE INDEX IF NOT EXISTS alerts_patient_open_idx
    ON alerts (patient_id, opened_at DESC) WHERE closed_at IS NULL;

CREATE TABLE IF NOT EXISTS alert_deliveries (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
    channel TEXT NOT NULL CHECK (channel IN ('telegram')),
    recipient TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'sent', 'failed')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (alert_id, channel, recipient)
);
