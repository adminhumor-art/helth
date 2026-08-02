import assert from "node:assert/strict";
import test from "node:test";

import { loadFamilyDashboard } from "../app/family-api.mjs";

const patientId = "11111111-1111-4111-8111-111111111111";
const nowEpochMs = Date.parse("2026-08-02T12:00:00Z");

function measurement(overrides = {}) {
  return {
    eventId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    patientId,
    sensorId: "SENSOR01",
    sensorFamily: "sibionics_gs1sb",
    sensorTime: "2026-08-02T11:59:00Z",
    phoneTime: "2026-08-02T11:59:01Z",
    receivedAt: "2026-08-02T11:59:02Z",
    glucoseMgDl: 92,
    trendMgDlPerMinute: -1,
    quality: "valid",
    sequence: 81,
    ...overrides,
  };
}

test("loads snapshot and bounded history through the server-side family session", async () => {
  const requests = [];
  const fetchImpl = async (url, init) => {
    requests.push({ url: String(url), init });
    if (String(url).endsWith(`/v1/patients/${patientId}/snapshot`)) {
      return Response.json({
        patientId,
        freshness: "fresh",
        latest: measurement(),
        openAlerts: [],
      });
    }
    return Response.json([measurement()]);
  };

  const result = await loadFamilyDashboard({
    backendOrigin: "https://api.example.test/",
    patientId,
    familySessionCookie: "family_session=secret-value",
    hours: 6,
    nowEpochMs,
    fetchImpl,
  });

  assert.equal(result.state, "ready");
  assert.equal(result.source, "live");
  assert.equal(result.latest.glucoseMgDl, 92);
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.equal(request.init.headers.cookie, "family_session=secret-value");
    assert.equal(request.init.cache, "no-store");
    assert.equal(request.init.redirect, "error");
  }
  const history = new URL(requests[1].url);
  assert.equal(history.searchParams.get("from"), "2026-08-02T06:00:00.000Z");
  assert.equal(history.searchParams.get("to"), "2026-08-02T12:00:00.000Z");
});

test("never falls back to demo values when a live request is unauthorized", async () => {
  const result = await loadFamilyDashboard({
    backendOrigin: "https://api.example.test",
    patientId,
    familySessionCookie: "family_session=expired",
    hours: 6,
    nowEpochMs,
    fetchImpl: async () => new Response("unauthorized", { status: 401 }),
  });

  assert.deepEqual(result, {
    source: "live",
    state: "unavailable",
    reason: "unauthorized",
    latest: null,
    chartSegments: [],
    openAlerts: [],
  });
});

test("rejects unsafe origin, patient identity, cookie and response size before display", async () => {
  let calls = 0;
  const fetchImpl = async () => {
    calls += 1;
    return new Response("{}", {
      headers: { "content-length": String(1024 * 1024 + 1) },
    });
  };

  for (const input of [
    { backendOrigin: "http://api.example.test", patientId, familySessionCookie: "family_session=x" },
    { backendOrigin: "https://api.example.test", patientId: "not-a-uuid", familySessionCookie: "family_session=x" },
    { backendOrigin: "https://api.example.test", patientId, familySessionCookie: "not_the_session=x" },
  ]) {
    const result = await loadFamilyDashboard({
      ...input,
      hours: 6,
      nowEpochMs,
      fetchImpl,
    });
    assert.equal(result.state, "unavailable");
    assert.equal(result.reason, "configuration");
  }
  assert.equal(calls, 0);

  const oversized = await loadFamilyDashboard({
    backendOrigin: "https://api.example.test",
    patientId,
    familySessionCookie: "family_session=x",
    hours: 6,
    nowEpochMs,
    fetchImpl,
  });
  assert.equal(oversized.state, "unavailable");
  assert.equal(oversized.reason, "invalid-response");
});
