import assert from "node:assert/strict";
import test from "node:test";

import { buildDemoSamples, formatHours, splitChartSegments } from "../app/glucose-data.mjs";

test("hour legend uses Russian plural forms", () => {
  assert.equal(formatHours(1), "1 час");
  assert.equal(formatHours(3), "3 часа");
  assert.equal(formatHours(6), "6 часов");
  assert.equal(formatHours(21), "21 час");
  assert.equal(formatHours(24), "24 часа");
});

test("demo series is deterministic and ends in a low value", () => {
  const first = buildDemoSamples(6);
  const second = buildDemoSamples(6);

  assert.deepEqual(first, second);
  assert.ok(first.length > 0);
  assert.ok(first.at(-1).value <= 70);
});

test("chart never draws a line across a missing-data interval", () => {
  const segments = splitChartSegments([
    { minute: 0, value: 100 },
    { minute: 5, value: 105 },
    { minute: 25, value: 90 },
    { minute: 30, value: 88 },
  ]);

  assert.deepEqual(segments, [
    [{ minute: 0, value: 100 }, { minute: 5, value: 105 }],
    [{ minute: 25, value: 90 }, { minute: 30, value: 88 }],
  ]);
});

test("invalid or non-increasing points cannot create a misleading connection", () => {
  const segments = splitChartSegments([
    { minute: 10, value: 100 },
    { minute: 10, value: 110 },
    { minute: 5, value: 95 },
    { minute: 15, value: Number.NaN },
  ]);

  assert.deepEqual(segments, [[{ minute: 10, value: 100 }]]);
});
