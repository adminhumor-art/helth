export type FamilyProxyFailure = {
  ok: false;
  reason: "configuration" | "denied" | "not-found" | "temporarily-unavailable" | "invalid-response";
};

export type FamilySessionExchangeResult = FamilyProxyFailure | {
  ok: true;
  familySessionValue: string;
  csrfToken: string;
  expiresAt: string;
};

export function exchangeFamilySession(input: {
  backendOrigin: string;
  requestOrigin: string;
  familyAccessToken: string;
  fetchImpl?: typeof fetch;
}): Promise<FamilySessionExchangeResult>;

export function acknowledgeFamilyAlert(input: {
  backendOrigin: string;
  requestOrigin: string;
  alertId: string;
  familySessionValue: string;
  csrfToken: string;
  fetchImpl?: typeof fetch;
}): Promise<FamilyProxyFailure | { ok: true }>;
