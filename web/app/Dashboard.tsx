"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { FamilyDashboardResult } from "./family-api.mjs";
import { createDashboardViewModel } from "./dashboard-data.mjs";
import { formatHours } from "./glucose-data.mjs";

const ranges = [3, 6, 24] as const;

type Props = {
  initialView: FamilyDashboardResult;
  patientLabel?: string;
};

function GlucoseChart({ view, hours }: { view: FamilyDashboardResult; hours: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const segments = view.chartSegments;
  const samples = useMemo(() => segments.flat(), [segments]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || samples.length === 0) return;

    const render = () => {
      const rect = canvas.getBoundingClientRect();
      const scale = window.devicePixelRatio || 1;
      canvas.width = Math.round(rect.width * scale);
      canvas.height = Math.round(rect.height * scale);
      const context = canvas.getContext("2d");
      if (!context) return;
      context.setTransform(scale, 0, 0, scale, 0, 0);

      const width = rect.width;
      const height = rect.height;
      const pad = { left: 8, right: 42, top: 12, bottom: 20 };
      const plotWidth = width - pad.left - pad.right;
      const plotHeight = height - pad.top - pad.bottom;
      const min = 40;
      const max = 280;
      const y = (value: number) => pad.top + ((max - value) / (max - min)) * plotHeight;
      const maximumMinute = Math.max(hours * 60 - 5, 1);
      const x = (minute: number) => pad.left + (minute / maximumMinute) * plotWidth;

      context.clearRect(0, 0, width, height);
      context.fillStyle = "rgba(140, 205, 180, 0.14)";
      context.fillRect(pad.left, y(180), plotWidth, y(70) - y(180));

      context.lineWidth = 1;
      context.font = "10px Arial";
      context.textAlign = "left";
      [50, 100, 150, 200, 250].forEach((tick) => {
        context.strokeStyle = tick === 50 ? "rgba(211, 73, 73, .24)" : "rgba(70, 105, 96, .12)";
        context.beginPath();
        context.moveTo(pad.left, y(tick));
        context.lineTo(pad.left + plotWidth, y(tick));
        context.stroke();
        context.fillStyle = "#7a8c87";
        context.fillText(String(tick), pad.left + plotWidth + 8, y(tick) + 3);
      });

      const gradient = context.createLinearGradient(0, pad.top, 0, height);
      gradient.addColorStop(0, "#176b57");
      gradient.addColorStop(0.76, "#176b57");
      gradient.addColorStop(1, "#d34949");
      context.strokeStyle = gradient;
      context.lineWidth = 3;
      context.lineJoin = "round";
      context.lineCap = "round";
      segments.forEach((segment) => {
        context.beginPath();
        segment.forEach((sample, index) => {
          if (index === 0) context.moveTo(x(sample.minute), y(sample.value));
          else context.lineTo(x(sample.minute), y(sample.value));
        });
        context.stroke();
      });

      context.fillStyle = "#176b57";
      samples.forEach((sample) => {
        context.beginPath();
        context.arc(x(sample.minute), y(sample.value), 1.25, 0, Math.PI * 2);
        context.fill();
      });

      const last = samples.at(-1);
      if (!last) return;
      context.fillStyle = last.value <= 70 ? "#d34949" : "#176b57";
      context.beginPath();
      context.arc(x(last.minute), y(last.value), 5, 0, Math.PI * 2);
      context.fill();
      context.strokeStyle = "white";
      context.lineWidth = 2;
      context.stroke();
    };

    const observer = new ResizeObserver(render);
    observer.observe(canvas);
    render();
    return () => observer.disconnect();
  }, [hours, samples, segments]);

  return <canvas ref={canvasRef} role="img" aria-label={`График глюкозы за ${formatHours(hours)}`} />;
}

export default function Dashboard({ initialView, patientLabel = "Мама" }: Props) {
  const router = useRouter();
  const [range, setRange] = useState<(typeof ranges)[number]>(6);
  const [view, setView] = useState(initialView);
  const [acknowledgedAlerts, setAcknowledgedAlerts] = useState<Set<string>>(new Set());
  const [acknowledging, setAcknowledging] = useState(false);
  const [acknowledgeError, setAcknowledgeError] = useState(false);

  useEffect(() => setView(initialView), [initialView]);

  const ready = view.state === "ready" ? view : null;
  const latest = ready?.latest ?? null;
  const currentAlert = ready?.openAlerts.find((alert) =>
    alert.acknowledgedAtEpochMs === null && !acknowledgedAlerts.has(alert.id)
  ) ?? null;
  const isDemo = view.source === "demo";
  const demoAttention = isDemo && latest !== null && (latest.glucoseMgDl <= 70 || latest.glucoseMgDl >= 250);
  const needsAttention = currentAlert !== null || demoAttention;
  const trend = latest === null ? 0 : latest.trendMgDlPerMinute || demoTrend(view);
  const status = latest === null
    ? "Нет свежих данных"
    : latest.glucoseMgDl <= 70
      ? "Низкое значение"
      : latest.glucoseMgDl >= 250
        ? "Высокое значение"
        : "В диапазоне";

  const selectRange = (hours: (typeof ranges)[number]) => {
    setRange(hours);
    if (isDemo) setView(createDashboardViewModel({ mode: "demo", hours }));
  };

  const acknowledge = async () => {
    if (isDemo) {
      setAcknowledgedAlerts(new Set(["demo"]));
      return;
    }
    if (!currentAlert || acknowledging) return;
    setAcknowledging(true);
    setAcknowledgeError(false);
    try {
      const response = await fetch(`/api/alerts/${currentAlert.id}/acknowledge`, {
        method: "POST",
        headers: { accept: "application/json" },
      });
      if (!response.ok) throw new Error("acknowledge failed");
      setAcknowledgedAlerts((current) => new Set(current).add(currentAlert.id));
      router.refresh();
    } catch {
      setAcknowledgeError(true);
    } finally {
      setAcknowledging(false);
    }
  };

  const demoAcknowledged = acknowledgedAlerts.has("demo");
  const alertAcknowledged = demoAcknowledged || (!isDemo && currentAlert === null && ready?.openAlerts.length !== 0);

  return (
    <main className="shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark" aria-hidden="true" />Сладкая</div>
        <div className="top-status">
          <span className={ready ? "online-dot" : "offline-dot"} aria-hidden="true" />
          <span>{isDemo ? "Демо-экран активен" : ready ? "Данные с телефона получены" : "Свежих данных нет"}</span>
          <span className="avatar" aria-label="Профиль владельца">Я</span>
        </div>
      </header>

      <section className={isDemo ? "demo-banner" : "live-banner"} aria-label={isDemo ? "Режим демонстрации" : "Живые данные"}>
        <strong>{isDemo ? "ДЕМО · СИМУЛЯЦИЯ" : "СЕМЕЙНЫЙ ДОСТУП"}</strong>
        <span>{isDemo
          ? "Все значения, тревоги, люди и статусы на этом экране — тестовые."
          : "Показаны последние проверенные сервером данные; при потере свежести число скрывается."}</span>
      </section>

      <section className="dashboard-head" aria-labelledby="page-title">
        <div>
          <p className="eyebrow">Семейное наблюдение</p>
          <h1 id="page-title">{patientLabel}</h1>
        </div>
        <label>
          <span className="sr-only">Выберите человека</span>
          <select className="patient-select" defaultValue="current" disabled>
            <option value="current">{patientLabel}{isDemo ? " · GS1Sb" : ""}</option>
          </select>
        </label>
      </section>

      <div className="main-grid">
        <section className="card glucose-card" aria-label="Состояние глюкозы">
          <div className="glucose-summary">
            <div>
              <span className={needsAttention ? "status-pill" : "status-pill normal"}>{status}</span>
              {latest ? (
                <>
                  <div className="glucose-value">
                    <strong>{formatMmol(latest.glucoseMgDl)}</strong><span>ммоль/л<br />{latest.glucoseMgDl} мг/дл</span>
                    <span className="trend-arrow" aria-label={trendLabel(trend)}>{trendArrow(trend)}</span>
                  </div>
                  <p className={needsAttention ? "trend-copy" : "trend-copy normal"}>{trendDescription(trend)}</p>
                </>
              ) : (
                <div className="unavailable-value" role="status"><strong>—</strong><p>{unavailableMessage(view.reason)}</p></div>
              )}
            </div>
            {needsAttention || alertAcknowledged ? (
              <aside className="alert-panel" aria-live="polite">
                <h2>{alertAcknowledged ? "Тревога подтверждена" : "Требуется внимание"}</h2>
                <p>{alertAcknowledged
                  ? "Семья отметила, что увидела эту тревогу."
                  : isDemo
                    ? "Симуляция: серверная и Telegram-тревога показаны только для проверки интерфейса."
                    : "Открытая тревога получена с семейного сервера."}</p>
                {!alertAcknowledged && (
                  <button className="acknowledge" onClick={acknowledge} disabled={acknowledging}>
                    {acknowledging ? "Подтверждаем…" : "Я увидел тревогу"}
                  </button>
                )}
                {acknowledgeError && <p className="inline-error">Не удалось подтвердить. Повторите ещё раз.</p>}
              </aside>
            ) : null}
          </div>

          <div className="chart-section">
            <div className="chart-head">
              <div><h2>История глюкозы</h2><p>{isDemo ? "Демо-диапазон тревог 3,9–10,0 ммоль/л · разрыв не соединяется линией" : "Пропуски данных не соединяются ложной линией"}</p></div>
              {isDemo ? (
                <div className="range-tabs" role="group" aria-label="Период графика">
                  {ranges.map((hours) => (
                    <button key={hours} className={range === hours ? "active" : ""} onClick={() => selectRange(hours)} aria-pressed={range === hours}>
                      {hours} ч
                    </button>
                  ))}
                </div>
              ) : <span className="range-label">6 ч</span>}
            </div>
            {ready && view.chartSegments.length > 0 ? (
              <>
                <div className="chart-wrap"><GlucoseChart view={view} hours={range} /></div>
                <div className="chart-legend"><span>{formatHours(range)} назад</span><span>Сейчас</span></div>
              </>
            ) : <div className="empty-chart">График появится после получения свежей истории.</div>}
          </div>
        </section>

        <aside className="side-column">
          <section className="card side-card">
            <h2>Связь и данные</h2>
            <div className="metric-list">
              <div className="metric"><span className="metric-icon" aria-hidden="true">◉</span><div><strong>Измерение</strong><span>{ready ? "Проверено и принято" : "Ожидается"}</span></div><span className={ready ? "metric-state" : "metric-state muted"}>{ready ? "Есть" : "Нет"}</span></div>
              <div className="metric"><span className="metric-icon" aria-hidden="true">▯</span><div><strong>Телефон</strong><span>{isDemo ? "Тестовый статус" : "Источник семейных данных"}</span></div><span className="metric-state">{isDemo ? "Демо" : ready ? "На связи" : "Нет данных"}</span></div>
              <div className="metric"><span className="metric-icon" aria-hidden="true">!</span><div><strong>Открытые тревоги</strong><span>На семейном сервере</span></div><span className={ready?.openAlerts.length ? "metric-state danger" : "metric-state"}>{ready?.openAlerts.length ?? 0}</span></div>
            </div>
          </section>

          <section className="card side-card">
            <h2>Последнее событие</h2>
            {latest ? (
              <div className={needsAttention ? "event-row critical" : "event-row"}>
                <strong>{status} · {formatMmol(latest.glucoseMgDl)}</strong>
                <p>{isDemo ? "Симулированное измерение" : formatUpdateTime(latest.sensorTimeEpochMs)}</p>
              </div>
            ) : <p className="side-empty">Без свежего показания. Старое число намеренно не показывается.</p>}
          </section>
        </aside>
      </div>
      <p className="demo-note">{isDemo
        ? "Сейчас показаны симулированные данные. Экран не выдаёт медицинских рекомендаций."
        : "Сервис помогает семье заметить тревогу, но не заменяет экстренную помощь или медицинское решение."}</p>
    </main>
  );
}

function demoTrend(view: FamilyDashboardResult): number {
  if (view.source !== "demo" || view.state !== "ready") return 0;
  const points = view.chartSegments.flat();
  const last = points.at(-1);
  const previous = points.at(-2);
  return last && previous ? (last.value - previous.value) / Math.max(last.minute - previous.minute, 1) : 0;
}

function formatMmol(mgDl: number): string {
  return (mgDl / 18).toFixed(1).replace(".", ",");
}

function trendArrow(trend: number): string {
  if (trend <= -2) return "↓";
  if (trend < -0.5) return "↘";
  if (trend >= 2) return "↑";
  if (trend > 0.5) return "↗";
  return "→";
}

function trendLabel(trend: number): string {
  if (trend <= -2) return "быстро снижается";
  if (trend < -0.5) return "снижается";
  if (trend >= 2) return "быстро растёт";
  if (trend > 0.5) return "растёт";
  return "стабильно";
}

function trendDescription(trend: number): string {
  const label = trendLabel(trend);
  if (Math.abs(trend) <= 0.5) return "Стабильно";
  const deltaMmol = Math.abs(trend * 5 / 18).toFixed(1).replace(".", ",");
  return `${label[0].toUpperCase()}${label.slice(1)} · ${trend < 0 ? "−" : "+"}${deltaMmol} ммоль/л за 5 минут`;
}

function unavailableMessage(reason: string | null): string {
  switch (reason) {
    case "unauthorized": return "Нужно войти в семейный доступ.";
    case "stale": return "Последнее измерение устарело.";
    case "missing": return "Телефон ещё не передал измерение.";
    case "not-ready": return "Датчик ещё не готов к показу значения.";
    case "clock-mismatch": return "Время телефона требует проверки.";
    case "offline":
    case "temporarily-unavailable": return "Сервер временно недоступен.";
    default: return "Достоверное текущее значение недоступно.";
  }
}

function formatUpdateTime(sensorTimeEpochMs: number): string {
  return new Intl.DateTimeFormat("ru-RU", {
    hour: "2-digit",
    minute: "2-digit",
    day: "2-digit",
    month: "2-digit",
    timeZone: "Europe/Minsk",
  }).format(new Date(sensorTimeEpochMs));
}
