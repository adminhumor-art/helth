import assert from "node:assert/strict";
import test from "node:test";

import { createDashboardViewModel } from "../app/dashboard-data.mjs";

const NOW = Date.parse("2026-08-01T12:00:00Z");
const PATIENT_ID = "00000000-0000-4000-8000-000000000100";
const ANOTHER_PATIENT_ID = "00000000-0000-4000-8000-000000000200";

function measurement(overrides = {}) {
  return {
    eventId: "00000000-0000-4000-8000-000000000001",
    patientId: PATIENT_ID,
    sensorId: "GS1SB-TEST",
    sensorFamily: "sibionics_gs1sb",
    sensorTime: "2026-08-01T11:59:00Z",
    phoneTime: "2026-08-01T11:59:05Z",
    receivedAt: "2026-08-01T11:59:06Z",
    glucoseMgDl: 58,
    trendMgDlPerMinute: -3,
    quality: "valid",
    sequence: 42,
    ...overrides,
  };
}

function snapshot(latest = measurement(), overrides = {}) {
  return {
    patientId: PATIENT_ID,
    freshness: "fresh",
    latest,
    openAlerts: [],
    ...overrides,
  };
}

function live(snapshotValue = snapshot(), measurements = [measurement()]) {
  return createDashboardViewModel(
    { mode: "live", snapshot: snapshotValue, measurements, hours: 6 },
    { nowEpochMs: NOW, expectedPatientId: PATIENT_ID },
  );
}

test("demo mode is explicit, deterministic, and keeps a real chart gap", () => {
  const first = createDashboardViewModel({ mode: "demo", hours: 6 }, { nowEpochMs: NOW });
  const second = createDashboardViewModel({ mode: "demo", hours: 6 }, { nowEpochMs: NOW });

  assert.deepEqual(first, second);
  assert.equal(first.source, "demo");
  assert.equal(first.state, "ready");
  assert.equal(first.reason, null);
  assert.ok(first.latest);
  assert.ok(first.chartSegments.length > 1);
});

test("unknown mode never falls back to plausible demo readings", () => {
  const result = createDashboardViewModel({ mode: "preview", hours: 6 }, { nowEpochMs: NOW });

  assert.equal(result.source, "live");
  assert.equal(result.state, "unavailable");
  assert.equal(result.reason, "invalid");
  assert.equal(result.latest, null);
  assert.deepEqual(result.chartSegments, []);
});

test("live data must match a trusted patient scope", () => {
  const missingScope = createDashboardViewModel(
    { mode: "live", snapshot: snapshot(), measurements: [measurement()], hours: 6 },
    { nowEpochMs: NOW },
  );
  const differentScope = createDashboardViewModel(
    { mode: "live", snapshot: snapshot(), measurements: [measurement()], hours: 6 },
    { nowEpochMs: NOW, expectedPatientId: ANOTHER_PATIENT_ID },
  );

  for (const result of [missingScope, differentScope]) {
    assert.equal(result.state, "unavailable");
    assert.equal(result.reason, "invalid");
    assert.equal(result.latest, null);
  }
});

test("fresh valid backend data becomes a typed live view model", () => {
  const result = live(
    snapshot(measurement({ sensorTime: "2026-08-01T11:59:00.123456789Z" })),
    [
      measurement({ eventId: "00000000-0000-4000-8000-000000000010", sensorTime: "2026-08-01T11:40:00Z", phoneTime: "2026-08-01T11:40:02Z", receivedAt: "2026-08-01T11:40:03Z", glucoseMgDl: 101, sequence: 40 }),
      measurement({ eventId: "00000000-0000-4000-8000-000000000011", sensorTime: "2026-08-01T11:45:00Z", phoneTime: "2026-08-01T11:45:02Z", receivedAt: "2026-08-01T11:45:03Z", glucoseMgDl: 93, sequence: 41 }),
      measurement({ eventId: "00000000-0000-4000-8000-000000000012", sensorTime: "2026-08-01T11:59:00Z", glucoseMgDl: 58, sequence: 42 }),
    ],
  );

  assert.equal(result.source, "live");
  assert.equal(result.state, "ready");
  assert.equal(result.reason, null);
  assert.deepEqual(result.latest, {
    glucoseMgDl: 58,
    trendMgDlPerMinute: -3,
    sensorTimeEpochMs: Date.parse("2026-08-01T11:59:00.123Z"),
  });
  assert.equal(result.chartSegments.length, 2);
  assert.deepEqual(result.chartSegments.map((segment) => segment.map((point) => point.value)), [[101, 93], [58]]);
});

test("maximum JSON-safe sequence remains representable in live data", () => {
  const latest = measurement({ sequence: Number.MAX_SAFE_INTEGER });
  const result = live(snapshot(latest), [latest]);

  assert.equal(result.state, "ready");
  assert.equal(result.latest?.glucoseMgDl, 58);
});

test("uppercase backend UUID remains valid", () => {
  const uppercase = measurement({
    eventId: "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE",
  });
  const result = live(snapshot(uppercase), [uppercase]);

  assert.equal(result.state, "ready");
  assert.equal(result.latest?.glucoseMgDl, 58);
});

test("invalid latest payload fails closed without leaking a plausible number", () => {
  for (const latest of [
    measurement({ glucoseMgDl: "58" }),
    measurement({ glucoseMgDl: 601 }),
    measurement({ sequence: 42.5 }),
    measurement({ sequence: Number.MAX_SAFE_INTEGER + 1 }),
    measurement({ receivedAt: "not-a-time" }),
    measurement({ sensorTime: "2026-02-31T11:59:00Z" }),
    measurement({ sensorFamily: "simulator" }),
  ]) {
    const result = live(snapshot(latest));
    assert.equal(result.state, "unavailable");
    assert.equal(result.reason, "invalid");
    assert.equal(result.latest, null);
  }
});

test("server freshness and local timestamp checks must both pass", () => {
  const markedStale = live(snapshot(measurement(), { freshness: "stale" }));
  assert.equal(markedStale.state, "unavailable");
  assert.equal(markedStale.reason, "stale");

  const independentlyStale = live(snapshot(measurement({
    sensorTime: "2026-08-01T11:49:59Z",
    phoneTime: "2026-08-01T11:59:00Z",
    receivedAt: "2026-08-01T11:59:01Z",
  })));
  assert.equal(independentlyStale.state, "unavailable");
  assert.equal(independentlyStale.reason, "stale");
  assert.equal(independentlyStale.latest, null);
});

test("warming-up and degraded values are never exposed as current glucose", () => {
  for (const quality of ["warming_up", "degraded"]) {
    const result = live(snapshot(measurement({ quality })));
    assert.equal(result.state, "unavailable");
    assert.equal(result.reason, "not-ready");
    assert.equal(result.latest, null);
  }
});

test("small server/browser drift is tolerated", () => {
  const drifted = measurement({
    phoneTime: "2026-08-01T12:00:30Z",
    receivedAt: "2026-08-01T12:00:30Z",
  });
  const result = live(snapshot(drifted), [drifted]);

  assert.equal(result.state, "ready");
  assert.equal(result.latest?.glucoseMgDl, 58);
});

test("meaningful future phone, receipt, or far-future sensor time is a clock mismatch", () => {
  for (const latest of [
    measurement({ phoneTime: "2026-08-01T12:01:00.001Z" }),
    measurement({ receivedAt: "2026-08-01T12:01:00.001Z" }),
    measurement({ sensorTime: "2026-08-01T12:05:00.001Z" }),
  ]) {
    const result = live(snapshot(latest));
    assert.equal(result.state, "unavailable");
    assert.equal(result.reason, "clock-mismatch");
    assert.equal(result.latest, null);
  }
});

test("newer valid history makes an older snapshot unavailable", () => {
  const result = live(
    snapshot(measurement({
      sensorTime: "2026-08-01T11:55:00Z",
      phoneTime: "2026-08-01T11:55:01Z",
      receivedAt: "2026-08-01T11:55:02Z",
      glucoseMgDl: 58,
      sequence: 41,
    })),
    [
      measurement({
        eventId: "00000000-0000-4000-8000-000000000042",
        sensorTime: "2026-08-01T11:59:00Z",
        phoneTime: "2026-08-01T11:59:01Z",
        receivedAt: "2026-08-01T11:59:02Z",
        glucoseMgDl: 240,
        sequence: 42,
      }),
    ],
  );

  assert.equal(result.state, "unavailable");
  assert.equal(result.reason, "inconsistent");
  assert.equal(result.latest, null);
  assert.deepEqual(result.chartSegments, []);
});

test("same event with conflicting immutable payload is unavailable", () => {
  const latest = measurement({ glucoseMgDl: 58 });
  const result = live(snapshot(latest), [measurement({ glucoseMgDl: 240 })]);

  assert.equal(result.state, "unavailable");
  assert.equal(result.reason, "inconsistent");
  assert.equal(result.latest, null);
});

test("unrepresentable sequence cannot hide a newer history reading", () => {
  const result = live(
    snapshot(measurement({
      sensorTime: "2026-08-01T11:55:00Z",
      phoneTime: "2026-08-01T11:55:01Z",
      receivedAt: "2026-08-01T11:55:02Z",
      sequence: 41,
    })),
    [measurement({
      eventId: "00000000-0000-4000-8000-000000000043",
      sensorTime: "2026-08-01T11:59:00Z",
      phoneTime: "2026-08-01T11:59:01Z",
      receivedAt: "2026-08-01T11:59:02Z",
      glucoseMgDl: 240,
      sequence: Number.MAX_SAFE_INTEGER + 1,
    })],
  );

  assert.equal(result.state, "unavailable");
  assert.equal(result.reason, "inconsistent");
  assert.equal(result.latest, null);
});

test("unrepresentable sequence cannot hide a competing same-time event", () => {
  const result = live(snapshot(), [measurement({
    eventId: "00000000-0000-4000-8000-000000000044",
    glucoseMgDl: 240,
    sequence: Number.MAX_SAFE_INTEGER + 1,
  })]);

  assert.equal(result.state, "unavailable");
  assert.equal(result.reason, "inconsistent");
  assert.equal(result.latest, null);
});

test("rejected history points create graph gaps instead of false lines", () => {
  const result = live(snapshot(), [
    measurement({ eventId: "00000000-0000-4000-8000-000000000020", sensorTime: "2026-08-01T11:45:00Z", phoneTime: "2026-08-01T11:45:01Z", receivedAt: "2026-08-01T11:45:02Z", glucoseMgDl: 100, sequence: 20 }),
    measurement({ eventId: "00000000-0000-4000-8000-000000000021", sensorTime: "2026-08-01T11:50:00Z", phoneTime: "2026-08-01T11:50:01Z", receivedAt: "2026-08-01T11:50:02Z", glucoseMgDl: 500, quality: "degraded", sequence: 21 }),
    measurement({ eventId: "00000000-0000-4000-8000-000000000022", sensorTime: "2026-08-01T11:55:00Z", phoneTime: "2026-08-01T11:55:01Z", receivedAt: "2026-08-01T11:55:02Z", glucoseMgDl: 90, sequence: 22 }),
  ]);

  assert.equal(result.state, "ready");
  assert.deepEqual(result.chartSegments.map((segment) => segment.map((point) => point.value)), [[100], [90]]);
});

test("sensor changes and sequence gaps always start a new graph segment", () => {
  const result = live(snapshot(), [
    measurement({ eventId: "00000000-0000-4000-8000-000000000030", sensorTime: "2026-08-01T11:40:00Z", phoneTime: "2026-08-01T11:40:01Z", receivedAt: "2026-08-01T11:40:02Z", sensorId: "SENSOR-A", glucoseMgDl: 100, sequence: 30 }),
    measurement({ eventId: "00000000-0000-4000-8000-000000000031", sensorTime: "2026-08-01T11:45:00Z", phoneTime: "2026-08-01T11:45:01Z", receivedAt: "2026-08-01T11:45:02Z", sensorId: "SENSOR-B", glucoseMgDl: 180, sequence: 31 }),
    measurement({ eventId: "00000000-0000-4000-8000-000000000032", sensorTime: "2026-08-01T11:50:00Z", phoneTime: "2026-08-01T11:50:01Z", receivedAt: "2026-08-01T11:50:02Z", sensorId: "SENSOR-B", glucoseMgDl: 120, sequence: 33 }),
    measurement({ eventId: "00000000-0000-4000-8000-000000000033", sensorTime: "2026-08-01T11:55:00Z", phoneTime: "2026-08-01T11:55:01Z", receivedAt: "2026-08-01T11:55:02Z", sensorId: "SENSOR-B", glucoseMgDl: 110, sequence: 34 }),
  ]);

  assert.deepEqual(result.chartSegments.map((segment) => segment.map((point) => point.value)), [
    [100],
    [180],
    [120, 110],
  ]);
});

test("missing or malformed history yields no chart but cannot hide a safe latest value", () => {
  const missing = createDashboardViewModel(
    { mode: "live", snapshot: snapshot(), measurements: null, hours: 6 },
    { nowEpochMs: NOW, expectedPatientId: PATIENT_ID },
  );

  assert.equal(missing.state, "ready");
  assert.equal(missing.latest?.glucoseMgDl, 58);
  assert.deepEqual(missing.chartSegments, []);
});
