import type { components } from "@/lib/api/generated/schema";

/**
 * The model of Option 1 of the Home page of Management Core, the plant board (ADR-0266): the types of what the server
 * sends, and the small arithmetic of drawing (a scale that fits the data, a line that breaks at a month with no score, a
 * dotted piece across the break, the words of a day). Pure: no component, no call. Every status, word and number is what
 * the server decided; this file adds up nothing about plans and holds no number of the organization.
 */

export type PlantBoard = components["schemas"]["ManagementPlantBoard"];
export type BoardPlant = components["schemas"]["ManagementBoardPlant"];
export type BoardGridRow = components["schemas"]["ManagementBoardGridRow"];
export type BoardCell = components["schemas"]["ManagementBoardCell"];
export type BoardPlan = components["schemas"]["ManagementBoardPlan"];
export type BoardPending = components["schemas"]["ManagementBoardPending"];
export type BoardKpis = components["schemas"]["ManagementBoardKpis"];

/** The months compared when the address names none: the board shows a year of months, as the pictures do. */
export const BOARD_DEFAULT_SPAN = 12;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-04" as "Apr"; a text that is not a month comes back as it is. */
export function monthAbbreviation(month: string): string {
  const found = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(month);
  return found?.[2] ? (MONTHS[Number(found[2]) - 1] ?? month) : month;
}

/** "2026-04" as "Apr 2026". */
export function monthWithYear(month: string): string {
  const found = /^(\d{4})-/.exec(month);
  return found?.[1] ? `${monthAbbreviation(month)} ${found[1]}` : month;
}

/** The label of a column: the month, with its year where the year changes (and on the first column). */
export function columnLabel(months: readonly string[], index: number): string {
  const month = months[index] ?? "";
  const year = month.slice(0, 4);
  const before = months[index - 1]?.slice(0, 4);
  return index === 0 || before !== year ? monthWithYear(month) : monthAbbreviation(month);
}

/** A day written as "6 Jul", with the year where it is not the year of {@code today}. A day not there is a dash. */
export function dayText(day: string | null | undefined, today: string): string {
  const found = day ? /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/.exec(day) : null;
  if (!found?.[1] || !found[2] || !found[3]) {
    return "–";
  }
  const base = `${Number(found[3])} ${MONTHS[Number(found[2]) - 1]}`;
  return found[1] === today.slice(0, 4) ? base : `${base} ${found[1]}`;
}

/** A score without a percent sign: whole when it is whole, else up to two decimals. A gap is a dash. */
export function scoreText(score: number | null | undefined): string {
  if (score === null || score === undefined) {
    return "–";
  }
  return String(Number(score.toFixed(2)));
}

/** A short name for the end of a line: "Plant 1" is "P1"; any other name is its first four letters. */
export function shortName(label: string): string {
  const found = /^(\S)\S*\s+(\d+)$/.exec(label.trim());
  return found?.[1] && found[2] ? `${found[1]}${found[2]}` : label.trim().slice(0, 4);
}

/** The words of the change under a tile: "up 2 vs Sep", "down 1 vs Sep", "0 vs Sep"; none without a change. */
export function changeText(plant: BoardPlant): { arrow: "up" | "down" | "same"; text: string } | undefined {
  if (plant.change === undefined || plant.change === null || !plant.changeMonth) {
    return undefined;
  }
  const size = scoreText(Math.abs(plant.change));
  const month = monthAbbreviation(plant.changeMonth);
  if (plant.change > 0) {
    return { arrow: "up", text: `${size} vs ${month}` };
  }
  return plant.change < 0 ? { arrow: "down", text: `${size} vs ${month}` } : { arrow: "same", text: `0 vs ${month}` };
}

// ---- the chart ----

/**
 * A scale that fits the data in steps of 10 and never passes 100: from the ten below the lowest score to the ten above
 * the highest, capped at 100. A score is a percent, so the axis stops at 100 and is not forced to start at 0.
 */
export function fitScale(scores: readonly (number | null | undefined)[]): { low: number; high: number; ticks: number[] } {
  const values = scores.filter((score): score is number => score !== null && score !== undefined);
  if (values.length === 0) {
    return { low: 0, high: 100, ticks: [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100] };
  }
  const high = Math.min(100, Math.ceil(Math.max(...values) / 10) * 10);
  let low = Math.max(0, Math.floor(Math.min(...values) / 10) * 10);
  if (low >= high) {
    low = Math.max(0, high - 10);
  }
  const ticks: number[] = [];
  for (let tick = low; tick <= high; tick += 10) {
    ticks.push(tick);
  }
  return { low, high, ticks };
}

/** The runs of consecutive months that have a score: a month with none ends a run (the line is open there). */
export function runsOfScores(scores: readonly (number | null | undefined)[]): number[][] {
  const runs: number[][] = [];
  let run: number[] = [];
  scores.forEach((score, index) => {
    if (score === null || score === undefined) {
      if (run.length > 0) {
        runs.push(run);
      }
      run = [];
    } else {
      run.push(index);
    }
  });
  if (run.length > 0) {
    runs.push(run);
  }
  return runs;
}

/** The two ends of each thin piece that joins a run to the next one across a gap: the last point and the next first. */
export function bridges(runs: readonly (readonly number[])[]): [number, number][] {
  const found: [number, number][] = [];
  for (let at = 0; at + 1 < runs.length; at += 1) {
    const before = runs[at];
    const after = runs[at + 1];
    const last = before?.[before.length - 1];
    const first = after?.[0];
    if (last !== undefined && first !== undefined) {
      found.push([last, first]);
    }
  }
  return found;
}

/** The path of one piece joining two points across a gap. The one place its shape is decided (a straight line). */
export function bridgePath(from: { x: number; y: number }, to: { x: number; y: number }): string {
  return `M${from.x},${from.y} L${to.x},${to.y}`;
}

/** The path of a line through points. */
export function linePath(points: readonly { x: number; y: number }[]): string {
  return points.map((point, index) => `${index === 0 ? "M" : "L"}${point.x},${point.y}`).join(" ");
}

/** Moves labels apart so that two never overlap: they keep their order and stay at least {@code gap} apart. */
export function spreadLabels(wanted: readonly number[], gap: number): number[] {
  const order = wanted.map((y, index) => ({ y, index })).sort((a, b) => a.y - b.y || a.index - b.index);
  const placed: number[] = [];
  order.forEach((entry, at) => {
    const before = at === 0 ? undefined : placed[at - 1];
    placed.push(before === undefined ? entry.y : Math.max(entry.y, before + gap));
  });
  const result = new Array<number>(wanted.length).fill(0);
  order.forEach((entry, at) => {
    result[entry.index] = placed[at] ?? entry.y;
  });
  return result;
}

/** The month nearest to a position along the plot, from 0 at the first month to {@code count - 1} at the last. */
export function nearestMonth(position: number, count: number): number {
  if (count <= 1) {
    return 0;
  }
  return Math.max(0, Math.min(count - 1, Math.round(position * (count - 1))));
}

/** The class of a plant's colour: a plant keeps its place in the colours in every widget; further places repeat. */
export function seriesVar(colour: number): string {
  return `var(--series-${((Math.max(colour, 1) - 1) % 6) + 1})`;
}

// ---- the tracker ----

export type PlanFilter = "all" | "overdue" | "submitted";

/** The rows of the detail table a chip shows; "Submitted" holds the plans sent late too. */
export function filterPlans(plans: readonly BoardPlan[], filter: PlanFilter): BoardPlan[] {
  if (filter === "overdue") {
    return plans.filter((plan) => plan.status === "OVERDUE");
  }
  if (filter === "submitted") {
    return plans.filter((plan) => plan.status === "SUBMITTED" || plan.status === "LATE");
  }
  return [...plans];
}

/** The class of a status: the same names colour a cell, a status pill and the key, so the key can never differ. */
export function statusClass(status: string): string {
  switch (status) {
    case "SUBMITTED":
      return "done";
    case "LATE":
      return "late";
    case "OVERDUE":
      return "over";
    case "AWAITING":
      return "wait";
    case "NO_SCORE":
      return "none";
    case "IN_REVIEW":
    case "NOT_SENT":
    case "NO_DATE":
      return "tbd";
    default:
      return "ok";
  }
}
