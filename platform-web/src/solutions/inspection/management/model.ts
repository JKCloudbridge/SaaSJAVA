import type { components } from "@/lib/api/generated/schema";
import { appBase } from "../appPaths";

/**
 * The model of the Home page that compares the plants (MC3, ADR-0262): types of what the server sends, the filters as
 * the address holds them, the links the tiles lead to, and the small arithmetic of drawing (a score on a 0 to 100 scale,
 * a line that breaks at a month with no score). Pure: no component, no call. The page draws what the server counted;
 * it adds up nothing and decides no permission, and it holds no number of the organization (the limit and the band
 * lines come in the answer).
 */

export type ManagementSummary = components["schemas"]["ManagementSummaryView"];
export type ManagementTile = components["schemas"]["ManagementTile"];
export type PlantScore = components["schemas"]["PlantScore"];
export type PlantSeries = components["schemas"]["PlantSeries"];
export type MonthAverage = components["schemas"]["MonthAverage"];
export type PlantPlans = components["schemas"]["PlantPlans"];
export type MonthStatus = components["schemas"]["MonthStatus"];

/** The months a comparison may cover; the server checks the same three. */
export const SPANS = [3, 6, 12] as const;
export type Span = (typeof SPANS)[number];
export const DEFAULT_SPAN: Span = 6;

/** What the filter bar holds; an empty text is "no filter". */
export interface ManagementFilters {
  /** `this`, `last` or `yyyy-MM`. */
  month: string;
  span: Span;
  plant: string;
  form: string;
}

export const NO_FILTERS: ManagementFilters = { month: "this", span: DEFAULT_SPAN, plant: "", form: "" };

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const FORM = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CODE = /^[A-Za-z0-9_-]{1,10}$/;

/** The filters an address holds; whatever is not one of the known shapes is "no filter". */
export function filtersFromParams(
  params: { get(name: string): string | null } | null | undefined,
  defaultSpan: Span = DEFAULT_SPAN,
): ManagementFilters {
  const month = params?.get("month") ?? "";
  const span = Number(params?.get("span"));
  const plant = params?.get("plant") ?? "";
  const form = params?.get("form") ?? "";
  return {
    month: month === "last" || MONTH.test(month) ? month : "this",
    span: (SPANS as readonly number[]).includes(span) ? (span as Span) : defaultSpan,
    plant: CODE.test(plant) ? plant : "",
    form: FORM.test(form) ? form : "",
  };
}

/** The address query of the filters, with only what differs from the default. */
export function paramsFromFilters(filters: ManagementFilters, defaultSpan: Span = DEFAULT_SPAN): string {
  const params = new URLSearchParams();
  if (filters.month !== "this") {
    params.set("month", filters.month);
  }
  if (filters.span !== defaultSpan) {
    params.set("span", String(filters.span));
  }
  if (filters.plant !== "") {
    params.set("plant", filters.plant);
  }
  if (filters.form !== "") {
    params.set("form", filters.form);
  }
  return params.toString();
}

export function isFiltered(filters: ManagementFilters, defaultSpan: Span = DEFAULT_SPAN): boolean {
  return paramsFromFilters(filters, defaultSpan) !== "";
}

// ---- where a number leads ----

/** The slices of the fill-in list the tiles lead to; the list decides again who sees what. */
export function managementLinks(application: string) {
  const list = `${appBase(application)}/s-fill-ins`;
  const to = (query: Record<string, string | undefined>): string => {
    const params = new URLSearchParams();
    Object.entries(query).forEach(([name, value]) => {
      if (value) {
        params.set(name, value);
      }
    });
    const text = params.toString();
    return text ? `${list}?${text}` : list;
  };
  return {
    sent: (month: string, plant: string) => to({ period: month, plant, sent: "1", plan: "none" }),
    scored: (month: string, plant: string) => to({ period: month, plant, status: "APPROVED", scored: "1", plan: "none" }),
    needs: (month: string, plant: string) => to({ period: month, plant, needs: "1" }),
    waiting: (kind: "review" | "top", plant: string) => to({ waiting: kind, plant }),
    plansOpen: (plant: string) => to({ plan: "open", plant }),
    plantMonth: (month: string, plant: string) => to({ period: month, plant, plan: "none" }),
  } as const;
}

export type ManagementLinks = ReturnType<typeof managementLinks>;

// ---- words ----

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-11" as "Nov 2026"; a text that is not a month comes back as it is. */
export function monthName(month: string): string {
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(month);
  return match ? `${MONTHS[Number(match[2]) - 1]} ${match[1]}` : month;
}

/** "2026-11" as "Nov", for the axis of a chart. */
export function monthShort(month: string): string {
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(month);
  return match ? (MONTHS[Number(match[2]) - 1] ?? month) : month;
}

/** A score as written: two decimals at most, no trailing zeros ("86.5%", "90%"); a score that is missing is a dash. */
export function formatScore(score: number | null | undefined): string {
  if (score === null || score === undefined) {
    return "—";
  }
  return `${Number(score.toFixed(2))}%`;
}

export function formatCount(value: number | null | undefined, atLeast = false): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return `${atLeast ? "at least " : ""}${Math.round(value).toLocaleString("en")}`;
}


// ---- drawing ----

/**
 * The runs of a series: a month with no score ends one run and starts the next, so the line has a gap there and is
 * never drawn down to zero. A run of one point is still a run (it is drawn as a dot).
 */
export function runsOf<T extends { score?: number | null }>(points: readonly T[]): { index: number; point: T }[][] {
  const runs: { index: number; point: T }[][] = [];
  let current: { index: number; point: T }[] = [];
  points.forEach((point, index) => {
    if (point.score === null || point.score === undefined) {
      if (current.length > 0) {
        runs.push(current);
        current = [];
      }
      return;
    }
    current.push({ index, point });
  });
  if (current.length > 0) {
    runs.push(current);
  }
  return runs;
}

/** The share of a 0 to 100 scale a score fills; a score outside the scale is held to its ends. */
export function scaleShare(score: number): number {
  return Math.min(100, Math.max(0, score));
}

/** The x of the n-th of `count` months along `width`; one month sits in the middle. */
export function monthX(index: number, count: number, width: number): number {
  return count <= 1 ? width / 2 : (index / (count - 1)) * width;
}

/** The y of a score on the 0 to 100 scale in a box of `height`, the top being 100. */
export function scoreY(score: number, height: number): number {
  return height - (scaleShare(score) / 100) * height;
}

/** The marks that tell series apart where colour alone would not: a shape for each series. */
export const MARKS = ["circle", "square", "triangle", "diamond", "cross", "plus"] as const;
export type Mark = (typeof MARKS)[number];

export function markOf(index: number): Mark {
  return MARKS[index % MARKS.length] ?? "circle";
}

/** The series colour a plant takes: the first six tokens in turn. */
export function seriesOf(index: number): number {
  return (index % 6) + 1;
}

/** A band as the page writes it, keyed by the server's key; the words themselves are the server's. */
export function bandTone(band: string | null | undefined): "good" | "neutral" | "bad" {
  return band === "EXCELLENT" ? "good" : band === "POOR" ? "bad" : "neutral";
}
