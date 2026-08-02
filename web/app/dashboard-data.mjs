import { buildDemoSamples, splitChartSegments } from "./glucose-data.mjs";

const DEFAULT_STALE_AFTER_MS = 10 * 60_000;
const MAX_SENSOR_FUTURE_SKEW_MS = 5 * 60_000;
const MAX_SERVER_CLOCK_SKEW_MS = 60_000;
const MAX_CONNECTED_GAP_MINUTES = 7.5;
const SENSOR_FAMILIES = new Set([
  "sibionics_gs1",
  "sibionics_gs1sb",
  "sibionics_gs3",
]);
const QUALITIES = new Set(["valid", "warming_up", "degraded"]);
const ALERT_KINDS = new Set(["low", "high", "rapid_fall", "rapid_rise", "signal_loss"]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CONTENT_EVENT_ID = /^[0-9a-f]{64}$/;
const RFC3339 = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;

/** @typedef {{ minute: number, value: number }} ChartPoint */
/** @typedef {{ glucoseMgDl: number, trendMgDlPerMinute: number, sensorTimeEpochMs: number }} LatestView */
/**
 * @typedef {"invalid" | "missing" | "stale" | "not-ready" | "clock-mismatch" | "inconsistent"} UnavailableReason
 */
/**
 * @typedef {{
 *   source: "demo" | "live",
 *   state: "ready" | "unavailable",
 *   reason: UnavailableReason | null,
 *   latest: LatestView | null,
 *   chartSegments: ChartPoint[][],
 * }} DashboardViewModel
 */

/**
 * Pure boundary between untrusted API JSON and product presentation.
 * Demo data is created only when the caller asks for the exact `demo` mode.
 *
 * @param {unknown} input
 * @param {{ nowEpochMs?: number, staleAfterMs?: number, expectedPatientId?: string }} [options]
 * @returns {DashboardViewModel}
 */
export function createDashboardViewModel(input, options = {}) {
  const mode = isRecord(input) ? input.mode : undefined;
  if (mode === "demo") return createDemoViewModel(input);
  if (mode !== "live") return unavailable("invalid");

  const nowEpochMs = options.nowEpochMs ?? Date.now();
  const staleAfterMs = options.staleAfterMs ?? DEFAULT_STALE_AFTER_MS;
  if (!Number.isFinite(nowEpochMs) || !Number.isFinite(staleAfterMs) || staleAfterMs <= 0 ||
      !isUuid(options.expectedPatientId)) {
    return unavailable("invalid");
  }

  return createLiveViewModel(input, options.expectedPatientId, nowEpochMs, staleAfterMs);
}

/** @param {Record<string, unknown>} input @returns {DashboardViewModel} */
function createDemoViewModel(input) {
  const hours = validHours(input.hours) ? input.hours : 6;
  const samples = buildDemoSamples(hours);
  const latest = samples.at(-1);
  return {
    source: "demo",
    state: latest ? "ready" : "unavailable",
    reason: latest ? null : "invalid",
    latest: latest
      ? { glucoseMgDl: latest.value, trendMgDlPerMinute: 0, sensorTimeEpochMs: 0 }
      : null,
    chartSegments: splitChartSegments(samples, MAX_CONNECTED_GAP_MINUTES),
    openAlerts: [],
  };
}

/**
 * @param {Record<string, unknown>} input
 * @param {string} expectedPatientId
 * @param {number} nowEpochMs
 * @param {number} staleAfterMs
 * @returns {DashboardViewModel}
 */
function createLiveViewModel(input, expectedPatientId, nowEpochMs, staleAfterMs) {
  if (!isRecord(input.snapshot)) return unavailable("invalid");
  const snapshot = input.snapshot;
  if (!sameUuid(snapshot.patientId, expectedPatientId) || !Array.isArray(snapshot.openAlerts)) {
    return unavailable("invalid");
  }
  const openAlerts = parseOpenAlerts(snapshot.openAlerts, expectedPatientId, nowEpochMs);
  if (openAlerts === null) return unavailable("invalid");
  if (snapshot.freshness === "missing" || snapshot.latest === null) {
    return unavailable("missing");
  }
  if (snapshot.freshness === "stale") return unavailable("stale");
  if (snapshot.freshness !== "fresh") return unavailable("invalid");

  const latest = parseMeasurement(snapshot.latest, expectedPatientId);
  if (latest === null) return unavailable("invalid");

  const clockState = clockMismatch(latest, nowEpochMs);
  if (clockState) return unavailable("clock-mismatch");
  if (isStale(latest, nowEpochMs, staleAfterMs)) return unavailable("stale");
  if (latest.quality !== "valid") return unavailable("not-ready");
  if (hasInconsistentHistory(input.measurements, expectedPatientId, latest, nowEpochMs)) {
    return unavailable("inconsistent");
  }

  const hours = validHours(input.hours) ? input.hours : 6;
  return {
    source: "live",
    state: "ready",
    reason: null,
    latest: {
      glucoseMgDl: latest.glucoseMgDl,
      trendMgDlPerMinute: latest.trendMgDlPerMinute,
      sensorTimeEpochMs: latest.sensorTimeEpochMs,
    },
    chartSegments: buildLiveChartSegments(
      input.measurements,
      expectedPatientId,
      hours,
      nowEpochMs,
    ),
    openAlerts,
  };
}

/**
 * @param {unknown[]} input
 * @param {string} patientId
 * @param {number} nowEpochMs
 */
function parseOpenAlerts(input, patientId, nowEpochMs) {
  const parsed = [];
  const identities = new Set();
  for (const value of input) {
    if (!isRecord(value) || !isUuid(value.id) || !sameUuid(value.patientId, patientId) ||
        typeof value.kind !== "string" || !ALERT_KINDS.has(value.kind)) return null;
    const openedAtEpochMs = parseRfc3339(value.openedAt);
    if (openedAtEpochMs === null || openedAtEpochMs > nowEpochMs + MAX_SERVER_CLOCK_SKEW_MS) return null;
    if (value.closedAt !== undefined && value.closedAt !== null) return null;
    const acknowledgedAtEpochMs = value.acknowledgedAt === undefined || value.acknowledgedAt === null
      ? null
      : parseRfc3339(value.acknowledgedAt);
    if (value.acknowledgedAt !== undefined && value.acknowledgedAt !== null &&
        (acknowledgedAtEpochMs === null || acknowledgedAtEpochMs < openedAtEpochMs ||
          acknowledgedAtEpochMs > nowEpochMs + MAX_SERVER_CLOCK_SKEW_MS)) return null;
    if (value.glucoseMgDl !== undefined && value.glucoseMgDl !== null &&
        (!Number.isInteger(value.glucoseMgDl) || value.glucoseMgDl < 20 || value.glucoseMgDl > 600)) return null;
    const identity = value.id.toLowerCase();
    if (identities.has(identity)) return null;
    identities.add(identity);
    parsed.push({
      id: identity,
      kind: value.kind,
      openedAtEpochMs,
      acknowledgedAtEpochMs,
      glucoseMgDl: value.glucoseMgDl ?? null,
    });
  }
  return parsed.sort((left, right) => left.openedAtEpochMs - right.openedAtEpochMs);
}

/**
 * Snapshot and history are loaded by separate requests. A newer valid history
 * item means the large current value would already be obsolete, so the combined
 * screen must wait for a coherent refresh.
 * @param {unknown} input
 * @param {string} patientId
 * @param {NonNullable<ReturnType<typeof parseMeasurement>>} latest
 * @param {number} nowEpochMs
 */
function hasInconsistentHistory(input, patientId, latest, nowEpochMs) {
  if (!Array.isArray(input)) return false;
  return input.some((value) => {
    const candidate = parseMeasurement(value, patientId);
    if (candidate !== null && candidate.quality === "valid" && !clockMismatch(candidate, nowEpochMs)) {
      if (candidate.eventId.toLowerCase() === latest.eventId.toLowerCase()) {
        return !sameMeasurementPayload(candidate, latest);
      }
      return candidate.sensorTimeEpochMs >= latest.sensorTimeEpochMs;
    }

    const envelope = parseReconciliationEnvelope(value, patientId);
    return envelope !== null && !clockMismatch(envelope, nowEpochMs) &&
      envelope.sensorTimeEpochMs >= latest.sensorTimeEpochMs;
  });
}

/**
 * Reads only enough of a rejected history item to ensure that a valid-looking
 * newer timestamp cannot disappear because JavaScript cannot safely represent
 * an out-of-contract integer field such as an unsafe sequence.
 * @param {unknown} input
 * @param {string} patientId
 */
function parseReconciliationEnvelope(input, patientId) {
  if (!isRecord(input) || !sameUuid(input.patientId, patientId) || input.quality !== "valid") return null;
  const sensorTimeEpochMs = parseRfc3339(input.sensorTime);
  const phoneTimeEpochMs = parseRfc3339(input.phoneTime);
  const receivedAtEpochMs = parseRfc3339(input.receivedAt);
  if (sensorTimeEpochMs === null || phoneTimeEpochMs === null || receivedAtEpochMs === null) return null;
  return { sensorTimeEpochMs, phoneTimeEpochMs, receivedAtEpochMs };
}

/**
 * @param {NonNullable<ReturnType<typeof parseMeasurement>>} left
 * @param {NonNullable<ReturnType<typeof parseMeasurement>>} right
 */
function sameMeasurementPayload(left, right) {
  return left.eventId.toLowerCase() === right.eventId.toLowerCase() &&
    left.sensorId === right.sensorId &&
    left.sensorFamily === right.sensorFamily &&
    left.sensorTimeEpochMs === right.sensorTimeEpochMs &&
    left.phoneTimeEpochMs === right.phoneTimeEpochMs &&
    left.receivedAtEpochMs === right.receivedAtEpochMs &&
    left.glucoseMgDl === right.glucoseMgDl &&
    left.trendMgDlPerMinute === right.trendMgDlPerMinute &&
    left.quality === right.quality &&
    left.sequence === right.sequence;
}

/**
 * @param {unknown} input
 * @param {string} patientId
 * @param {number} hours
 * @param {number} nowEpochMs
 * @returns {ChartPoint[][]}
 */
function buildLiveChartSegments(input, patientId, hours, nowEpochMs) {
  if (!Array.isArray(input)) return [];
  const rangeStart = nowEpochMs - hours * 60 * 60_000;
  /** @type {ChartPoint[]} */
  const candidates = [];
  /** @type {ReturnType<typeof parseMeasurement>} */
  let previous = null;
  for (const value of input) {
    const measurement = parseMeasurement(value, patientId);
    if (measurement === null || measurement.quality !== "valid" || clockMismatch(measurement, nowEpochMs)) {
      candidates.push(invalidChartPoint());
      previous = null;
      continue;
    }
    if (measurement.sensorTimeEpochMs < rangeStart || measurement.sensorTimeEpochMs > nowEpochMs + MAX_SENSOR_FUTURE_SKEW_MS) {
      candidates.push(invalidChartPoint());
      previous = null;
      continue;
    }
    if (previous !== null && (
      previous.sensorId !== measurement.sensorId ||
      previous.sensorFamily !== measurement.sensorFamily ||
      measurement.sequence !== previous.sequence + 1
    )) {
      candidates.push(invalidChartPoint());
    }
    candidates.push({
      minute: (measurement.sensorTimeEpochMs - rangeStart) / 60_000,
      value: measurement.glucoseMgDl,
    });
    previous = measurement;
  }
  return splitChartSegments(candidates, MAX_CONNECTED_GAP_MINUTES);
}

/** @returns {ChartPoint} */
function invalidChartPoint() {
  return { minute: Number.NaN, value: Number.NaN };
}

/**
 * @param {unknown} input
 * @param {string} expectedPatientId
 * @returns {{
 *   glucoseMgDl: number,
 *   trendMgDlPerMinute: number,
 *   eventId: string,
 *   quality: "valid" | "warming_up" | "degraded",
 *   sensorId: string,
 *   sensorFamily: string,
 *   sequence: number,
 *   sensorTimeEpochMs: number,
 *   phoneTimeEpochMs: number,
 *   receivedAtEpochMs: number,
 * } | null}
 */
function parseMeasurement(input, expectedPatientId) {
  if (!isRecord(input)) return null;
  if (!isEventId(input.eventId)) return null;
  if (!sameUuid(input.patientId, expectedPatientId)) return null;
  if (!isNonEmptyString(input.sensorId) || input.sensorId.length > 128) return null;
  if (typeof input.sensorFamily !== "string" || !SENSOR_FAMILIES.has(input.sensorFamily)) return null;
  if (typeof input.quality !== "string" || !QUALITIES.has(input.quality)) return null;
  if (!Number.isInteger(input.glucoseMgDl) || input.glucoseMgDl < 20 || input.glucoseMgDl > 600) return null;
  if (!Number.isFinite(input.trendMgDlPerMinute) || Math.abs(input.trendMgDlPerMinute) > 20) return null;
  if (!Number.isSafeInteger(input.sequence) || input.sequence < 0) return null;

  const sensorTimeEpochMs = parseRfc3339(input.sensorTime);
  const phoneTimeEpochMs = parseRfc3339(input.phoneTime);
  const receivedAtEpochMs = parseRfc3339(input.receivedAt);
  if (sensorTimeEpochMs === null || phoneTimeEpochMs === null || receivedAtEpochMs === null) return null;

  return {
    glucoseMgDl: input.glucoseMgDl,
    trendMgDlPerMinute: input.trendMgDlPerMinute,
    eventId: input.eventId,
    quality: input.quality,
    sensorId: input.sensorId,
    sensorFamily: input.sensorFamily,
    sequence: input.sequence,
    sensorTimeEpochMs,
    phoneTimeEpochMs,
    receivedAtEpochMs,
  };
}

/**
 * Handles the RFC3339 nanoseconds emitted by Go while preserving millisecond
 * precision supported by JavaScript runtimes.
 * @param {unknown} value
 * @returns {number | null}
 */
function parseRfc3339(value) {
  if (typeof value !== "string") return null;
  const match = RFC3339.exec(value);
  if (match === null || !validRfc3339Parts(match[1], match[3])) return null;
  const fraction = match[2] ? `.${match[2].padEnd(3, "0").slice(0, 3)}` : "";
  const epochMs = Date.parse(`${match[1]}${fraction}${match[3]}`);
  return Number.isFinite(epochMs) ? epochMs : null;
}

/** @param {string} dateTime @param {string} zone */
function validRfc3339Parts(dateTime, zone) {
  const year = Number(dateTime.slice(0, 4));
  const month = Number(dateTime.slice(5, 7));
  const day = Number(dateTime.slice(8, 10));
  const hour = Number(dateTime.slice(11, 13));
  const minute = Number(dateTime.slice(14, 16));
  const second = Number(dateTime.slice(17, 19));
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > daysInMonth[month - 1]) return false;
  if (hour > 23 || minute > 59 || second > 59) return false;
  if (zone === "Z") return true;
  const offsetHour = Number(zone.slice(1, 3));
  const offsetMinute = Number(zone.slice(4, 6));
  return offsetHour <= 23 && offsetMinute <= 59;
}

/**
 * @param {{ sensorTimeEpochMs: number, phoneTimeEpochMs: number, receivedAtEpochMs: number }} value
 * @param {number} nowEpochMs
 */
function clockMismatch(value, nowEpochMs) {
  return value.sensorTimeEpochMs > nowEpochMs + MAX_SENSOR_FUTURE_SKEW_MS ||
    value.phoneTimeEpochMs > nowEpochMs + MAX_SERVER_CLOCK_SKEW_MS ||
    value.receivedAtEpochMs > nowEpochMs + MAX_SERVER_CLOCK_SKEW_MS;
}

/**
 * @param {{ sensorTimeEpochMs: number, phoneTimeEpochMs: number, receivedAtEpochMs: number }} value
 * @param {number} nowEpochMs
 * @param {number} staleAfterMs
 */
function isStale(value, nowEpochMs, staleAfterMs) {
  return nowEpochMs - value.sensorTimeEpochMs >= staleAfterMs ||
    nowEpochMs - value.phoneTimeEpochMs >= staleAfterMs ||
    nowEpochMs - value.receivedAtEpochMs >= staleAfterMs;
}

/** @param {UnavailableReason} reason @returns {DashboardViewModel} */
function unavailable(reason) {
  return {
    source: "live",
    state: "unavailable",
    reason,
    latest: null,
    chartSegments: [],
    openAlerts: [],
  };
}

/** @param {unknown} value @returns {value is Record<string, unknown>} */
function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** @param {unknown} value @returns {value is string} */
function isNonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

/** @param {unknown} value @returns {value is string} */
function isUuid(value) {
  return typeof value === "string" && UUID.test(value);
}

/** @param {unknown} left @param {unknown} right */
function sameUuid(left, right) {
  return isUuid(left) && isUuid(right) && left.toLowerCase() === right.toLowerCase();
}

/** @param {unknown} value @returns {value is string} */
function isEventId(value) {
  return typeof value === "string" && (UUID.test(value) || CONTENT_EVENT_ID.test(value));
}

/** @param {unknown} value @returns {value is number} */
function validHours(value) {
  return Number.isInteger(value) && value >= 1 && value <= 24;
}
