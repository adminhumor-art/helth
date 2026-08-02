import assert from "node:assert/strict";
import test from "node:test";

import {
  acknowledgeFamilyAlert,
  exchangeFamilySession,
} from "../app/family-session-proxy.mjs";

const alertId = "11111111-1111-4111-8111-111111111111";

test("exchanges provisioned access without returning either secret to browser code", async () => {
  let captured;
  const result = await exchangeFamilySession({
    backendOrigin: "https://api.example.test",
    requestOrigin: "https://family.example.test",
    familyAccessToken: "provisioned-secret",
    fetchImpl: async (url, init) => {
      captured = { url: String(url), init };
      return Response.json(
        { csrfToken: "csrf-secret", expiresAt: "2026-08-03T12:00:00Z" },
        {
          status: 201,
          headers: {
            "set-cookie": "family_session=session-secret; Path=/; Max-Age=86400; HttpOnly; Secure; SameSite=Strict",
          },
        },
      );
    },
  });

  assert.deepEqual(result, {
    ok: true,
    familySessionValue: "session-secret",
    csrfToken: "csrf-secret",
    expiresAt: "2026-08-03T12:00:00Z",
  });
  assert.equal(captured.url, "https://api.example.test/v1/family/session");
  assert.equal(captured.init.method, "POST");
  assert.equal(captured.init.headers.authorization, "Bearer provisioned-secret");
  assert.equal(captured.init.headers.origin, "https://family.example.test");
  assert.equal(captured.init.redirect, "error");
});

test("rejects a weakened or malformed backend session response", async () => {
  for (const response of [
    Response.json({ csrfToken: "csrf", expiresAt: "2026-08-03T12:00:00Z" }, { status: 201 }),
    Response.json(
      { csrfToken: "csrf", expiresAt: "2026-08-03T12:00:00Z" },
      { status: 201, headers: { "set-cookie": "family_session=x; Path=/; Secure" } },
    ),
    Response.json(
      { csrfToken: "", expiresAt: "2026-08-03T12:00:00Z" },
      { status: 201, headers: { "set-cookie": "family_session=x; Path=/; HttpOnly; Secure; SameSite=Strict" } },
    ),
  ]) {
    const result = await exchangeFamilySession({
      backendOrigin: "https://api.example.test",
      requestOrigin: "https://family.example.test",
      familyAccessToken: "provisioned-secret",
      fetchImpl: async () => response,
    });
    assert.deepEqual(result, { ok: false, reason: "invalid-response" });
  }
});

test("acknowledge forwards only the bound session, csrf and exact site origin", async () => {
  let captured;
  const result = await acknowledgeFamilyAlert({
    backendOrigin: "https://api.example.test/",
    requestOrigin: "https://family.example.test",
    alertId,
    familySessionValue: "session-secret",
    csrfToken: "csrf-secret",
    fetchImpl: async (url, init) => {
      captured = { url: String(url), init };
      return new Response(null, { status: 204 });
    },
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(captured.url, `https://api.example.test/v1/alerts/${alertId}/acknowledge`);
  assert.equal(captured.init.headers.cookie, "family_session=session-secret");
  assert.equal(captured.init.headers["x-csrf-token"], "csrf-secret");
  assert.equal(captured.init.headers.origin, "https://family.example.test");
});

test("invalid proxy inputs fail before network and never become a successful acknowledge", async () => {
  let calls = 0;
  const fetchImpl = async () => {
    calls += 1;
    return new Response(null, { status: 204 });
  };
  const exchange = await exchangeFamilySession({
    backendOrigin: "http://api.example.test",
    requestOrigin: "https://family.example.test",
    familyAccessToken: "secret",
    fetchImpl,
  });
  const acknowledge = await acknowledgeFamilyAlert({
    backendOrigin: "https://api.example.test",
    requestOrigin: "https://family.example.test/path",
    alertId: "not-a-uuid",
    familySessionValue: "session",
    csrfToken: "csrf",
    fetchImpl,
  });

  assert.deepEqual(exchange, { ok: false, reason: "configuration" });
  assert.deepEqual(acknowledge, { ok: false, reason: "configuration" });
  assert.equal(calls, 0);
});
