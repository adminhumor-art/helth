/** @typedef {{ minute: number, value: number }} Sample */

/**
 * @param {number} hours
 * @returns {string}
 */
export function formatHours(hours) {
  const absolute = Math.abs(hours);
  const lastTwo = absolute % 100;
  const last = absolute % 10;
  const noun = lastTwo >= 11 && lastTwo <= 14
    ? "часов"
    : last === 1
      ? "час"
      : last >= 2 && last <= 4
        ? "часа"
        : "часов";
  return `${hours} ${noun}`;
}

/**
 * Deterministic synthetic series used only by the public demo surface.
 * A deliberate gap exercises the same no-false-line rule required for live data.
 * @param {number} hours
 * @returns {Sample[]}
 */
export function buildDemoSamples(hours) {
  if (!Number.isInteger(hours) || hours < 1 || hours > 24) return [];
  const count = hours * 12;
  const gapStart = Math.floor(count * 0.64);
  const gapEnd = gapStart + 3;
  return Array.from({ length: count }, (_, index) => {
    const phase = index / Math.max(count - 1, 1);
    const baseline = 112 + Math.sin(phase * Math.PI * 5) * 16;
    const meal = Math.exp(-Math.pow((phase - 0.47) * 8, 2)) * 82;
    const nightLow = Math.exp(-Math.pow((phase - 0.985) * 13, 2)) * 56;
    return { minute: index * 5, value: Math.round(baseline + meal - nightLow) };
  }).filter((_, index) => index < gapStart || index >= gapEnd);
}

/**
 * Keeps source order and starts a new line after every real time gap.
 * Invalid and non-increasing points are rejected rather than rearranged.
 * @param {Sample[]} samples
 * @param {number} [maximumConnectedGapMinutes]
 * @returns {Sample[][]}
 */
export function splitChartSegments(samples, maximumConnectedGapMinutes = 7.5) {
  /** @type {Sample[][]} */
  const segments = [];
  /** @type {Sample[] | null} */
  let active = null;
  let previousMinute = -1;

  for (const sample of samples) {
    const valid = Number.isFinite(sample?.minute) &&
      Number.isFinite(sample?.value) &&
      sample.minute >= 0 &&
      sample.value >= 20 &&
      sample.value <= 600 &&
      sample.minute > previousMinute;
    if (!valid) {
      active = null;
      continue;
    }
    if (active === null || sample.minute - previousMinute > maximumConnectedGapMinutes) {
      active = [];
      segments.push(active);
    }
    active.push(sample);
    previousMinute = sample.minute;
  }
  return segments;
}
