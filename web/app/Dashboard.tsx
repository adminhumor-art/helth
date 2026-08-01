"use client";

import { useEffect, useMemo, useRef, useState } from "react";

type Sample = { minute: number; value: number };

const ranges = [3, 6, 24] as const;

function buildSamples(hours: number): Sample[] {
  const count = hours * 12;
  return Array.from({ length: count }, (_, index) => {
    const phase = index / Math.max(count - 1, 1);
    const baseline = 112 + Math.sin(phase * Math.PI * 5) * 16;
    const meal = Math.exp(-Math.pow((phase - 0.47) * 8, 2)) * 82;
    const nightLow = Math.exp(-Math.pow((phase - 0.94) * 13, 2)) * 62;
    return { minute: index * 5, value: Math.round(baseline + meal - nightLow) };
  });
}

function GlucoseChart({ hours }: { hours: number }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const samples = useMemo(() => buildSamples(hours), [hours]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

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
      const x = (index: number) => pad.left + (index / Math.max(samples.length - 1, 1)) * plotWidth;

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
      context.beginPath();
      samples.forEach((sample, index) => {
        if (index === 0) context.moveTo(x(index), y(sample.value));
        else context.lineTo(x(index), y(sample.value));
      });
      context.stroke();

      const last = samples[samples.length - 1];
      context.fillStyle = last.value <= 70 ? "#d34949" : "#176b57";
      context.beginPath();
      context.arc(x(samples.length - 1), y(last.value), 5, 0, Math.PI * 2);
      context.fill();
      context.strokeStyle = "white";
      context.lineWidth = 2;
      context.stroke();
    };

    const observer = new ResizeObserver(render);
    observer.observe(canvas);
    render();
    return () => observer.disconnect();
  }, [samples]);

  return <canvas ref={canvasRef} role="img" aria-label={`График глюкозы за ${hours} часов`} />;
}

export default function Dashboard() {
  const [range, setRange] = useState<(typeof ranges)[number]>(6);
  const [acknowledged, setAcknowledged] = useState(false);

  return (
    <main className="shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark" aria-hidden="true" />Сладкая</div>
        <div className="top-status">
          <span className="online-dot" aria-hidden="true" />
          <span>Все системы на связи</span>
          <span className="avatar" aria-label="Профиль Михаила">МИ</span>
        </div>
      </header>

      <section className="demo-banner" aria-label="Режим демонстрации">
        <strong>ДЕМО · СИМУЛЯЦИЯ</strong>
        <span>Все значения, тревоги, люди и статусы на этом экране — тестовые.</span>
      </section>

      <section className="dashboard-head" aria-labelledby="page-title">
        <div>
          <p className="eyebrow">Семейное наблюдение</p>
          <h1 id="page-title">Добрый вечер</h1>
        </div>
        <label>
          <span className="sr-only">Выберите человека</span>
          <select className="patient-select" defaultValue="mother">
            <option value="mother">Мама · SiBionics GS1Sb</option>
          </select>
        </label>
      </section>

      <div className="main-grid">
        <section className="card glucose-card" aria-label="Состояние глюкозы">
          <div className="glucose-summary">
            <div>
              <span className="status-pill">Низкое значение</span>
              <div className="glucose-value">
                <strong>3,2</strong><span>ммоль/л<br />58 мг/дл</span>
                <span className="trend-arrow" aria-label="снижается">↓</span>
              </div>
              <p className="trend-copy">Снижается · −0,3 ммоль/л за 5 минут</p>
            </div>
            <aside className="alert-panel" aria-live="polite">
              <h2>{acknowledged ? "Тревога подтверждена" : "Требуется внимание"}</h2>
              <p>{acknowledged ? "В демо-режиме тревога отмечена как просмотренная." : "Симуляция: Telegram отмечен как отправленный двум родственникам."}</p>
              <button
                className={`acknowledge${acknowledged ? " done" : ""}`}
                onClick={() => setAcknowledged(true)}
                disabled={acknowledged}
              >
                {acknowledged ? "Уведомление принято" : "Я увидел тревогу"}
              </button>
            </aside>
          </div>

          <div className="chart-section">
            <div className="chart-head">
              <div><h2>История глюкозы</h2><p>Целевой диапазон 3,9–10,0 ммоль/л</p></div>
              <div className="range-tabs" role="group" aria-label="Период графика">
                {ranges.map((hours) => (
                  <button key={hours} className={range === hours ? "active" : ""} onClick={() => setRange(hours)} aria-pressed={range === hours}>
                    {hours} ч
                  </button>
                ))}
              </div>
            </div>
            <div className="chart-wrap"><GlucoseChart hours={range} /></div>
            <div className="chart-legend"><span>{range} часов назад</span><span>Сейчас · обновлено минуту назад</span></div>
          </div>
        </section>

        <aside className="side-column">
          <section className="card side-card">
            <h2>Связь и устройства</h2>
            <div className="metric-list">
              <div className="metric"><span className="metric-icon" aria-hidden="true">◉</span><div><strong>Датчик</strong><span>GS1Sb · осталось 11 дней</span></div><span className="metric-state">На связи</span></div>
              <div className="metric"><span className="metric-icon" aria-hidden="true">▯</span><div><strong>Телефон мамы</strong><span>Заряд 64% · интернет есть</span></div><span className="metric-state">Онлайн</span></div>
              <div className="metric"><span className="metric-icon" aria-hidden="true">✦</span><div><strong>Telegram</strong><span>Два получателя</span></div><span className="metric-state">Готов</span></div>
            </div>
          </section>

          <section className="card side-card">
            <h2>Семья</h2>
            <div className="family-list">
              <div className="family-member"><div className="member-profile"><span className="member-avatar">РМ</span><div><strong>Ринат</strong><span>Владелец группы</span></div></div><time>онлайн</time></div>
              <div className="family-member"><div className="member-profile"><span className="member-avatar">АК</span><div><strong>Анна</strong><span>Родственник</span></div></div><time>5 мин</time></div>
            </div>
          </section>

          <section className="card side-card">
            <h2>Последние события</h2>
            <div className="event-row critical"><strong>Низкая глюкоза · 3,2</strong><p>Сейчас · Telegram отправлен</p></div>
            <div className="event-row"><strong>Связь восстановлена</strong><p>Сегодня, 21:44 · перерыв 2 минуты</p></div>
            <div className="event-row"><strong>Значение вернулось в диапазон</strong><p>Сегодня, 18:17</p></div>
          </section>
        </aside>
      </div>
      <p className="demo-note">Сейчас показаны симулированные данные. Экран не выдаёт медицинских рекомендаций.</p>
    </main>
  );
}
