import { appBase, DEFAULT_APPLICATION } from "../appPaths";
import type { components } from "@/lib/api/generated/schema";

/**
 * The pure parts of the home page of the Quality Management System (FA9, ADR-0203): which period and filters the address
 * names, how a number and its change are written, and the geometry of the graphs. Nothing here reads the API or the
 * browser, so every rule is tested without either, and nothing here decides what a person may see: the server counted
 * what the person may see, and this only writes it down.
 */

export type HomeSummary = components["schemas"]["SummaryView"];
export type HomeTile = components["schemas"]["Tile"];
export type HomeActivity = components["schemas"]["Activity"];

export const PERIODS = [
  { key: "7d", label: "7 days", words: "7 days" },
  { key: "30d", label: "30 days", words: "30 days" },
  { key: "90d", label: "90 days", words: "90 days" },
  { key: "12m", label: "12 months", words: "12 months" },
] as const;

export type PeriodKey = (typeof PERIODS)[number]["key"];

export const DEFAULT_PERIOD: PeriodKey = "30d";

/** What the address holds: the period and at most one location and one department. */
export interface HomeFilters {
  period: PeriodKey;
  location: string;
  department: string;
}

export const NO_FILTERS: HomeFilters = { period: DEFAULT_PERIOD, location: "", department: "" };

/** The period an address names; anything else is the default. */
export function periodOf(value: string | null | undefined): PeriodKey {
  return PERIODS.find((period) => period.key === value)?.key ?? DEFAULT_PERIOD;
}

/** The filters an address names. A value is only a code to send back: the server checks it against what is offered. */
export function filtersFromParams(params: { get(name: string): string | null } | null | undefined): HomeFilters {
  return {
    period: periodOf(params?.get("period")),
    location: (params?.get("location") ?? "").trim(),
    department: (params?.get("department") ?? "").trim(),
  };
}

/** The query text of an address for these filters; the default period and an empty choice are left out. */
export function paramsFromFilters(filters: HomeFilters): string {
  const query = new URLSearchParams();
  if (filters.period !== DEFAULT_PERIOD) {
    query.set("period", filters.period);
  }
  if (filters.location !== "") {
    query.set("location", filters.location);
  }
  if (filters.department !== "") {
    query.set("department", filters.department);
  }
  return query.toString();
}

/** Whether the choice differs from the one the page opens with: the Clear button is drawn only then. */
export function isFiltered(filters: HomeFilters): boolean {
  return filters.period !== DEFAULT_PERIOD || filters.location !== "" || filters.department !== "";
}

/** The words for the period, as in "the 30 days before". */
export function periodWords(key: string): string {
  return PERIODS.find((period) => period.key === key)?.words ?? key;
}

// ---- numbers and their change ----

/** A count with thousands separators; "at least" when the server stopped reading before the end. */
export function formatCount(value: number | undefined, atLeast = false): string {
  if (value === undefined) {
    return "–";
  }
  const text = Math.round(value).toLocaleString("en-GB");
  return atLeast ? `at least ${text}` : text;
}

/** Days with one decimal, and "day" for exactly one. */
export function formatDays(value: number | undefined): string {
  if (value === undefined) {
    return "–";
  }
  const text = Number.isInteger(value) ? String(value) : value.toFixed(1);
  return `${text} ${value === 1 ? "day" : "days"}`;
}

/** A percentage with one decimal at most. */
export function formatPercent(value: number | undefined): string {
  if (value === undefined) {
    return "–";
  }
  return `${Number.isInteger(value) ? value : value.toFixed(1)}%`;
}

export type Direction = "up" | "down" | "same" | "none";

/** How a number moved against the period before it, in words that do not depend on colour. */
export interface Change {
  direction: Direction;
  /** "Up 25% on the 30 days before", "Down 3 on the 30 days before", "No change", or "" when there is nothing to compare. */
  words: string;
  /** The difference, positive or negative, or null when there is nothing to compare. */
  difference: number | null;
}

/**
 * The change of a number against the period before. A change is written as a percentage of the earlier number when that
 * number was above zero, and as the plain difference when it was zero (a percentage of nothing says nothing). When
 * either number is missing there is nothing honest to say, so the words are empty.
 *
 * @param unit how a difference reads: a count, days or percentage points
 */
export function describeChange(
  current: number | undefined,
  previous: number | undefined,
  periodKey: string,
  unit: "count" | "days" | "points" = "count",
): Change {
  if (current === undefined || previous === undefined) {
    return { direction: "none", words: "", difference: null };
  }
  const difference = Math.round((current - previous) * 10) / 10;
  const against = `on the ${periodWords(periodKey)} before`;
  if (difference === 0) {
    return { direction: "same", words: `No change ${against}`, difference: 0 };
  }
  const direction: Direction = difference > 0 ? "up" : "down";
  const word = direction === "up" ? "Up" : "Down";
  const size = Math.abs(difference);
  if (unit === "days") {
    return { direction, words: `${word} ${formatDays(size)} ${against}`, difference };
  }
  if (unit === "points") {
    return { direction, words: `${word} ${size.toFixed(Number.isInteger(size) ? 0 : 1)} points ${against}`, difference };
  }
  if (previous > 0) {
    return { direction, words: `${word} ${Math.round((size / previous) * 100)}% ${against}`, difference };
  }
  return { direction, words: `${word} ${formatCount(size)} ${against}`, difference };
}

/** Whether a move in this direction is good, bad or neither for the tile; colour only repeats what the words say. */
export type Tone = "good" | "bad" | "neutral";

export function toneOf(direction: Direction, higherIs: "better" | "worse" | "neither"): Tone {
  if (direction === "none" || direction === "same" || higherIs === "neither") {
    return "neutral";
  }
  const better = higherIs === "better" ? direction === "up" : direction === "down";
  return better ? "good" : "bad";
}

// ---- the day, the month, the clock ----

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "5 Oct" for {@code 2026-10-05}. */
export function dayLabel(day: string): string {
  const [, month = "1", date = "1"] = day.split("-");
  return `${Number(date)} ${MONTHS[Number(month) - 1] ?? ""}`;
}

/** "Oct 26" for {@code 2026-10}. */
export function monthLabel(month: string): string {
  const [year = "", number = "1"] = month.split("-");
  return `${MONTHS[Number(number) - 1] ?? ""} ${year.slice(2)}`;
}

/** Which of the days get a label on the axis: at most {@code most}, evenly, always the first and the last. */
export function axisIndexes(count: number, most: number): number[] {
  if (count <= 0) {
    return [];
  }
  if (count <= most) {
    return Array.from({ length: count }, (_, index) => index);
  }
  const step = (count - 1) / (most - 1);
  return Array.from({ length: most }, (_, index) => Math.round(index * step));
}

/** The time of day of a moment in the person's own clock: "14:05". */
export function clockTime(moment: string): string {
  const date = new Date(moment);
  return Number.isNaN(date.getTime())
    ? ""
    : `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

/** A moment as people read it in a list: "5 Oct, 14:05". */
export function momentLabel(moment: string): string {
  const date = new Date(moment);
  return Number.isNaN(date.getTime()) ? "" : `${date.getDate()} ${MONTHS[date.getMonth()] ?? ""}, ${clockTime(moment)}`;
}

// ---- the geometry of the graphs ----

/** The top of an axis: the smallest "round" number (1, 2, 2.5, 5 times a power of ten) that holds the largest value. */
export function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const power = 10 ** Math.floor(Math.log10(value));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (value <= step * power) {
      return step * power;
    }
  }
  return 10 * power;
}

/** Axis values from zero to the top, {@code count} + 1 of them. */
export function ticks(top: number, count = 4): number[] {
  return Array.from({ length: count + 1 }, (_, index) => (top / count) * index);
}

export interface Point {
  x: number;
  y: number;
}

/** The points of a line over a box: values left to right, the largest at the top, none below the bottom. */
export function linePoints(values: readonly number[], width: number, height: number, top: number): Point[] {
  const last = Math.max(1, values.length - 1);
  return values.map((value, index) => ({
    x: values.length === 1 ? width / 2 : (index / last) * width,
    y: height - (Math.max(0, value) / (top <= 0 ? 1 : top)) * height,
  }));
}

/** The path of a line through the points. */
export function linePath(points: readonly Point[]): string {
  return points.map((point, index) => `${index === 0 ? "M" : "L"}${round(point.x)} ${round(point.y)}`).join(" ");
}

/** The path of the area under the line, closed along the bottom. */
export function areaPath(points: readonly Point[], height: number): string {
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) {
    return "";
  }
  return `${linePath(points)} L${round(last.x)} ${height} L${round(first.x)} ${height} Z`;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

export interface Arc {
  /** The share of the whole, 0 to 1. */
  share: number;
  /** Where the arc starts, as a share of the circle, 0 to 1. */
  start: number;
}

/** The arcs of a ring: each item's share of the total, in order; an empty ring has no arcs. */
export function ringArcs(counts: readonly number[]): Arc[] {
  const total = counts.reduce((sum, count) => sum + Math.max(0, count), 0);
  if (total === 0) {
    return counts.map(() => ({ share: 0, start: 0 }));
  }
  let start = 0;
  return counts.map((count) => {
    const share = Math.max(0, count) / total;
    const arc = { share, start };
    start += share;
    return arc;
  });
}

/** The length of a bar as a share of the longest, 0 to 100; a bar of zero is zero, never a sliver. */
export function barPercents(values: readonly number[]): number[] {
  const most = Math.max(0, ...values);
  return values.map((value) => (most <= 0 ? 0 : Math.round((Math.max(0, value) / most) * 1000) / 10));
}

/** The share of a part in a whole as a whole number of percent; empty when there is no whole. */
export function shareOf(part: number, whole: number): number {
  return whole <= 0 ? 0 : Math.round((part / whole) * 100);
}

/** The number of the series colour for the n-th series: 1 to 6, wrapping. */
export function seriesNumber(index: number): number {
  return (index % 6) + 1;
}

/** The pattern of the n-th series, so two series never differ by colour alone. */
export const PATTERNS = ["solid", "stripes", "dots", "cross", "wave", "grid"] as const;

// ---- where a number leads ----

/** The pages the tiles and graphs lead to. Each opens a list the person is allowed to see, and the list decides again. */
export function homeLinks(application: string) {
  const base = appBase(application);
  return {
    approvals: "/approvals",
    requests: `${base}/s-change-requests`,
    templates: (state?: string): string =>
      state ? `${base}/s-templates?state=${encodeURIComponent(state)}` : `${base}/s-templates`,
    fills: (status?: string): string =>
      status ? `${base}/s-fill-ins?status=${encodeURIComponent(status)}` : `${base}/s-fill-ins`,
    fillsOfTemplate: (templateId: string): string =>
      `${base}/s-fill-ins?template=${encodeURIComponent(templateId)}`,
  } as const;
}

/** The links of one application's home page. */
export type HomeLinks = ReturnType<typeof homeLinks>;

/** The links of the first application that shows these pages (the default of a render outside an address). */
export const HOME_LINKS = homeLinks(DEFAULT_APPLICATION);

/** The state a status chart asks the list of templates for: the server's key for "no revision yet" is NONE in the list. */
export function stateForList(key: string): string {
  return key === "NO_SHEET" ? "NONE" : key;
}

/** Where an item of the activity list leads: the template or the fill-in it is about. */
export function activityHref(
  activity: Pick<HomeActivity, "about" | "id">,
  application: string = DEFAULT_APPLICATION,
): string {
  const base = appBase(application);
  return activity.about === "fill-in"
    ? `${base}/s-fill-ins?open=${encodeURIComponent(activity.id)}`
    : `${base}/s-templates?open=${encodeURIComponent(activity.id)}`;
}

/** What an item of the activity list says happened, in a few words. */
export function activityWords(kind: string): string {
  switch (kind) {
    case "PUBLISHED":
      return "was published";
    case "REVISED":
      return "has a new revision in use";
    case "UNPUBLISHED":
      return "was taken back";
    case "RETIRED":
      return "was retired";
    case "SUBMITTED":
      return "was sent for review";
    default:
      return "changed";
  }
}
