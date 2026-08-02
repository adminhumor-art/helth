import Dashboard from "./Dashboard";
import FamilyAccessGate from "./FamilyAccessGate";
import { createDashboardViewModel } from "./dashboard-data.mjs";
import { loadFamilyDashboard } from "./family-api.mjs";
import { cookies } from "next/headers";

export const dynamic = "force-dynamic";

export default async function Home() {
  const backendOrigin = process.env.SLADKAYA_BACKEND_ORIGIN;
  const patientId = process.env.SLADKAYA_PATIENT_ID;
  const patientLabel = normalizedLabel(process.env.SLADKAYA_PATIENT_LABEL);
  if (!backendOrigin && !patientId) {
    return <Dashboard initialView={createDashboardViewModel({ mode: "demo", hours: 6 })} patientLabel={patientLabel} />;
  }
  if (!backendOrigin || !patientId) {
    return <Dashboard initialView={unavailable("configuration")} patientLabel={patientLabel} />;
  }

  const cookieStore = await cookies();
  const familySession = cookieStore.get("family_session")?.value;
  if (!familySession) return <FamilyAccessGate />;
  const view = await loadFamilyDashboard({
    backendOrigin,
    patientId,
    familySessionCookie: `family_session=${familySession}`,
    hours: 6,
  });
  if (view.state === "unavailable" && view.reason === "unauthorized") {
    return <FamilyAccessGate sessionExpired />;
  }
  return <Dashboard initialView={view} patientLabel={patientLabel} />;
}

function normalizedLabel(value: string | undefined): string {
  const label = value?.trim();
  return label && label.length <= 80 ? label : "Мама";
}

function unavailable(reason: "configuration") {
  return {
    source: "live" as const,
    state: "unavailable" as const,
    reason,
    latest: null,
    chartSegments: [] as [],
    openAlerts: [] as [],
  };
}
