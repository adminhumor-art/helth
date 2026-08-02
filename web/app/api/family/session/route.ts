import { NextResponse } from "next/server";
import { exchangeFamilySession } from "../../../family-session-proxy.mjs";

const MAX_REQUEST_BYTES = 8 * 1024;

export async function POST(request: Request) {
  const requestOrigin = new URL(request.url).origin;
  if (request.headers.get("origin") !== requestOrigin) {
    return problem(403, "origin rejected");
  }
  const backendOrigin = process.env.SLADKAYA_BACKEND_ORIGIN;
  if (!backendOrigin) return problem(503, "family backend is not configured");

  const length = Number(request.headers.get("content-length"));
  if (Number.isFinite(length) && length > MAX_REQUEST_BYTES) return problem(413, "request is too large");
  if (!(request.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) {
    return problem(415, "json is required");
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return problem(400, "invalid json");
  }
  const access = isRecord(body) ? body.access : undefined;
  if (typeof access !== "string" || access.length === 0 || access.length > 4096) {
    return problem(400, "access is required");
  }

  const result = await exchangeFamilySession({
    backendOrigin,
    requestOrigin,
    familyAccessToken: access,
  });
  if (!result.ok) {
    const status = result.reason === "denied" ? 401
      : result.reason === "configuration" ? 503
        : 502;
    return problem(status, "family session could not be created");
  }

  const expires = new Date(result.expiresAt);
  const response = NextResponse.json({ authenticated: true }, { status: 201 });
  response.headers.set("cache-control", "no-store");
  response.cookies.set("family_session", result.familySessionValue, {
    httpOnly: true,
    secure: true,
    sameSite: "strict",
    path: "/",
    expires,
  });
  response.cookies.set("sladkaya_csrf", result.csrfToken, {
    httpOnly: true,
    secure: true,
    sameSite: "strict",
    path: "/",
    expires,
  });
  return response;
}

function problem(status: number, detail: string) {
  return NextResponse.json({ detail }, { status, headers: { "cache-control": "no-store" } });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
