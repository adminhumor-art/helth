import { NextResponse } from "next/server";
import { acknowledgeFamilyAlert } from "../../../../family-session-proxy.mjs";

export async function POST(
  request: Request,
  context: { params: Promise<{ alertId: string }> },
) {
  const requestOrigin = new URL(request.url).origin;
  if (request.headers.get("origin") !== requestOrigin) return problem(403, "origin rejected");
  const backendOrigin = process.env.SLADKAYA_BACKEND_ORIGIN;
  const cookies = parseCookies(request.headers.get("cookie"));
  const familySessionValue = cookies.get("family_session");
  const csrfToken = cookies.get("sladkaya_csrf");
  if (!backendOrigin) return problem(503, "family backend is not configured");
  if (!familySessionValue || !csrfToken) return problem(401, "family session is required");

  const { alertId } = await context.params;
  const result = await acknowledgeFamilyAlert({
    backendOrigin,
    requestOrigin,
    alertId,
    familySessionValue,
    csrfToken,
  });
  if (result.ok) return new NextResponse(null, { status: 204, headers: { "cache-control": "no-store" } });

  const status = result.reason === "denied" ? 401
    : result.reason === "not-found" ? 404
      : result.reason === "configuration" ? 400
        : 502;
  const response = problem(status, "alert could not be acknowledged");
  if (status === 401) {
    response.cookies.delete("family_session");
    response.cookies.delete("sladkaya_csrf");
  }
  return response;
}

function parseCookies(header: string | null): Map<string, string> {
  const values = new Map<string, string>();
  for (const part of (header ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator <= 0) continue;
    const name = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (!values.has(name)) values.set(name, value);
  }
  return values;
}

function problem(status: number, detail: string) {
  return NextResponse.json({ detail }, { status, headers: { "cache-control": "no-store" } });
}
