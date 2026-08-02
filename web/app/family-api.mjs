import { createDashboardViewModel } from "./dashboard-data.mjs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FAMILY_SESSION = /^family_session=([!#$%&'*+\-.^_`|~0-9A-Za-z]{1,4096})$/;
const MAX_RESPONSE_BYTES = 1024 * 1024;

/**
 * Server-only loader. It forwards only the dedicated family session cookie,
 * never exposes that value to client code and never substitutes demo data for
 * a failed live request.
 *
 * @param {{
 *   backendOrigin: string,
 *   patientId: string,
 *   familySessionCookie: string,
 *   hours: number,
 *   nowEpochMs?: number,
 *   fetchImpl?: typeof fetch,
 * }} input
 */
export async function loadFamilyDashboard(input) {
  const normalized = normalizeConfiguration(input);
  if (normalized === null) return unavailable("configuration");

  const { origin, patientId, familySessionCookie, hours, nowEpochMs, fetchImpl } = normalized;
  const patientPath = `/v1/patients/${patientId}`;
  const historyUrl = new globalThis.URL(`${patientPath}/measurements`, origin);
  historyUrl.searchParams.set("from", new Date(nowEpochMs - hours * 60 * 60_000).toISOString());
  historyUrl.searchParams.set("to", new Date(nowEpochMs).toISOString());

  const request = {
    method: "GET",
    headers: {
      accept: "application/json",
      cookie: familySessionCookie,
    },
    cache: "no-store",
    redirect: "error",
  };

  try {
    const [snapshotResponse, historyResponse] = await Promise.all([
      fetchImpl(new globalThis.URL(`${patientPath}/snapshot`, origin), request),
      fetchImpl(historyUrl, request),
    ]);
    const statusFailure = classifyStatus(snapshotResponse, historyResponse);
    if (statusFailure !== null) return unavailable(statusFailure);

    const [snapshot, measurements] = await Promise.all([
      readBoundedJson(snapshotResponse),
      readBoundedJson(historyResponse),
    ]);
    if (snapshot === INVALID_JSON || measurements === INVALID_JSON) {
      return unavailable("invalid-response");
    }
    return createDashboardViewModel(
      { mode: "live", snapshot, measurements, hours },
      { expectedPatientId: patientId, nowEpochMs },
    );
  } catch {
    return unavailable("offline");
  }
}

/** @param {Parameters<typeof loadFamilyDashboard>[0]} input */
function normalizeConfiguration(input) {
  if (input === null || typeof input !== "object") return null;
  if (typeof input.backendOrigin !== "string" || typeof input.patientId !== "string" ||
      typeof input.familySessionCookie !== "string") return null;
  if (!UUID.test(input.patientId) || !FAMILY_SESSION.test(input.familySessionCookie)) return null;
  if (!Number.isInteger(input.hours) || input.hours < 1 || input.hours > 24) return null;

  const nowEpochMs = input.nowEpochMs ?? Date.now();
  if (!Number.isFinite(nowEpochMs)) return null;
  let parsed;
  try {
    parsed = new globalThis.URL(input.backendOrigin);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.pathname !== "/" ||
      parsed.search || parsed.hash) return null;

  return {
    origin: parsed.origin,
    patientId: input.patientId.toLowerCase(),
    familySessionCookie: input.familySessionCookie,
    hours: input.hours,
    nowEpochMs,
    fetchImpl: input.fetchImpl ?? globalThis.fetch,
  };
}

/** @param {Response} first @param {Response} second */
function classifyStatus(first, second) {
  const statuses = [first.status, second.status];
  if (statuses.includes(401) || statuses.includes(403)) return "unauthorized";
  if (statuses.includes(404)) return "not-found";
  if (statuses.some((status) => status === 429 || status >= 500)) return "temporarily-unavailable";
  if (!first.ok || !second.ok) return "invalid-response";
  return null;
}

const INVALID_JSON = Symbol("invalid-json");

/** @param {Response} response */
async function readBoundedJson(response) {
  if (!(response.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) {
    return INVALID_JSON;
  }
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) return INVALID_JSON;
  if (response.body === null) return INVALID_JSON;

  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_RESPONSE_BYTES) {
      await reader.cancel();
      return INVALID_JSON;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new globalThis.TextDecoder().decode(bytes));
  } catch {
    return INVALID_JSON;
  }
}

/** @param {string} reason */
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
