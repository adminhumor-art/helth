export type Sample = {
  minute: number;
  value: number;
};

export function buildDemoSamples(hours: number): Sample[];

export function formatHours(hours: number): string;

export function splitChartSegments(
  samples: Sample[],
  maximumConnectedGapMinutes?: number,
): Sample[][];
