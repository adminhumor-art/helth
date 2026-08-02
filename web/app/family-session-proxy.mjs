const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]{1,4096}$/;
const MAX_SESSION_AGE_SECONDS = 31 * 24 * 60 * 60;
const MAX_RESPONSE_BYTES = 16 * 1024;

/**
 * Exchanges a provisioned family access secret from a server-side BFF. The
 * caller must turn both returned values into HttpOnly same-origin cookies and
 * return only a success/failure marker to browser JavaScript.
 *
 * @param {{
 *   backendOrigin: string,
 *   requestOrigin: string,
 *   familyAccessToken: string,
 *   fetchImpl?: typeof fetch,
 * }} input
 */
export async function exchangeFamilySession(input) {
  const config = normalizeCommon(input);
  if (config === null || !validToken(input?.familyAccessToken)) return failure("configuration");
  try {
    const response = await config.fetchImpl(
      new globalThis.URL("/v1/family/session", config.backendOrigin),
      {
        method: "POST",
        headers: {
          accept: "application/json",
          authorization: `Bearer ${input.familyAccessToken}`,
          origin: config.requestOrigin,
        },
        cache: "no-store",
        redirect: "error",
      },
    );
    if (response.status === 401 || response.status === 403) return failure("denied");
    if (response.status === 429 || response.status >= 500) return failure("temporarily-unavailable");
    if (response.status !== 201) return failure("invalid-response");

    const session = parseStrictSessionCookie(response.headers.get("set-cookie"));
    const body = await readBoundedJson(response);
    if (session === null || body === null || !isRecord(body) ||
        !validToken(body.csrfToken) || typeof body.expiresAt !== "string" ||
        !Number.isFinite(Date.parse(body.expiresAt))) return failure("invalid-response");
    return {
      ok: true,
      familySessionValue: session,
      csrfToken: body.csrfToken,
      expiresAt: body.expiresAt,
    };
  } catch {
    return failure("temporarily-unavailable");
  }
}

/**
 * @param {{
 *   backendOrigin: string,
 *   requestOrigin: string,
 *   alertId: string,
 *   familySessionValue: string,
 *   csrfToken: string,
 *   fetchImpl?: typeof fetch,
 * }} input
 */
export async function acknowledgeFamilyAlert(input) {
  const config = normalizeCommon(input);
  if (config === null || typeof input?.alertId !== "string" || !UUID.test(input.alertId) ||
      !validToken(input.familySessionValue) || !validToken(input.csrfToken)) {
    return failure("configuration");
  }
  try {
    const response = await config.fetchImpl(
      new globalThis.URL(`/v1/alerts/${input.alertId.toLowerCase()}/acknowledge`, config.backendOrigin),
      {
        method: "POST",
        headers: {
          accept: "application/json",
          cookie: `family_session=${input.familySessionValue}`,
          origin: config.requestOrigin,
          "x-csrf-token": input.csrfToken,
        },
        cache: "no-store",
        redirect: "error",
      },
    );
    if (response.status === 204) return { ok: true };
    if (response.status === 401 || response.status === 403) return failure("denied");
    if (response.status === 404) return failure("not-found");
    if (response.status === 429 || response.status >= 500) return failure("temporarily-unavailable");
    return failure("invalid-response");
  } catch {
    return failure("temporarily-unavailable");
  }
}

/** @param {unknown} input */
function normalizeCommon(input) {
  if (!isRecord(input) || typeof input.backendOrigin !== "string" ||
      typeof input.requestOrigin !== "string") return null;
  const backendOrigin = exactOrigin(input.backendOrigin, true);
  const requestOrigin = exactOrigin(input.requestOrigin, false);
  if (backendOrigin === null || requestOrigin === null) return null;
  return { backendOrigin, requestOrigin, fetchImpl: input.fetchImpl ?? globalThis.fetch };
}

/** @param {string} value @param {boolean} requireHttps */
function exactOrigin(value, requireHttps) {
  let parsed;
  try {
    parsed = new globalThis.URL(value);
  } catch {
    return null;
  }
  if (parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash) return null;
  if (requireHttps && parsed.protocol !== "https:") return null;
  if (!requireHttps && parsed.protocol !== "https:" &&
      !(parsed.protocol === "http:" && (parsed.hostname === "localhost" || parsed.hostname === "127.0.0.1"))) return null;
  return parsed.origin;
}

/** @param {string | null} header */
function parseStrictSessionCookie(header) {
  if (typeof header !== "string") return null;
  const parts = header.split(";").map((part) => part.trim()).filter(Boolean);
  const pair = parts.shift();
  if (pair === undefined || !pair.startsWith("family_session=")) return null;
  const value = pair.slice("family_session=".length);
  if (!validToken(value)) return null;

  const attributes = new Map();
  for (const part of parts) {
    const separator = part.indexOf("=");
    const name = (separator === -1 ? part : part.slice(0, separator)).toLowerCase();
    const attributeValue = separator === -1 ? true : part.slice(separator + 1);
    if (attributes.has(name)) return null;
    attributes.set(name, attributeValue);
  }
  const maxAge = Number(attributes.get("max-age"));
  if (attributes.get("httponly") !== true || attributes.get("secure") !== true ||
      String(attributes.get("samesite")).toLowerCase() !== "strict" ||
      attributes.get("path") !== "/" || !Number.isInteger(maxAge) || maxAge <= 0 ||
      maxAge > MAX_SESSION_AGE_SECONDS) return null;
  return value;
}

/** @param {Response} response */
async function readBoundedJson(response) {
  if (!(response.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) return null;
  const length = Number(response.headers.get("content-length"));
  if (Number.isFinite(length) && length > MAX_RESPONSE_BYTES) return null;
  const text = await response.text();
  if (new globalThis.TextEncoder().encode(text).byteLength > MAX_RESPONSE_BYTES) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** @param {unknown} value */
function validToken(value) {
  return typeof value === "string" && TOKEN.test(value);
}

/** @param {unknown} value */
function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** @param {string} reason */
function failure(reason) {
  return { ok: false, reason };
}
