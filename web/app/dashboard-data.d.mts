import type { Sample } from "./glucose-data.mjs";

export type DataSource = "demo" | "live";
export type UnavailableReason = "invalid" | "missing" | "stale" | "not-ready" | "clock-mismatch" | "inconsistent";

export type LatestGlucoseView = {
  glucoseMgDl: number;
  trendMgDlPerMinute: number;
  sensorTimeEpochMs: number;
};

export type ReadyDashboardViewModel = {
  source: DataSource;
  state: "ready";
  reason: null;
  latest: LatestGlucoseView;
  chartSegments: Sample[][];
};

export type UnavailableDashboardViewModel = {
  source: DataSource;
  state: "unavailable";
  reason: UnavailableReason;
  latest: null;
  chartSegments: Sample[][];
};

export type DashboardViewModel = ReadyDashboardViewModel | UnavailableDashboardViewModel;

export type DashboardDataOptions = {
  nowEpochMs?: number;
  staleAfterMs?: number;
  expectedPatientId?: string;
};

export function createDashboardViewModel(
  input: unknown,
  options?: DashboardDataOptions,
): DashboardViewModel;
