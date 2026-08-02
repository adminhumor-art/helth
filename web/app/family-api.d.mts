import type { DashboardViewModel } from "./dashboard-data.mjs";

export type FamilyDashboardUnavailableReason =
  | "configuration"
  | "unauthorized"
  | "not-found"
  | "temporarily-unavailable"
  | "invalid-response"
  | "offline";

export type FamilyDashboardResult = DashboardViewModel | {
  source: "live";
  state: "unavailable";
  reason: FamilyDashboardUnavailableReason;
  latest: null;
  chartSegments: [];
  openAlerts: [];
};

export type FamilyDashboardRequest = {
  backendOrigin: string;
  patientId: string;
  familySessionCookie: string;
  hours: number;
  nowEpochMs?: number;
  fetchImpl?: typeof fetch;
};

export function loadFamilyDashboard(input: FamilyDashboardRequest): Promise<FamilyDashboardResult>;
