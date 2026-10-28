import { describe, expect, it } from "vitest";
import {
  areaPath,
  axisIndexes,
  barPercents,
  dayLabel,
  describeChange,
  filtersFromParams,
  formatCount,
  formatDays,
  formatPercent,
  isFiltered,
  linePath,
  linePoints,
  monthLabel,
  niceMax,
  NO_FILTERS,
  paramsFromFilters,
  periodOf,
  ringArcs,
  seriesNumber,
  shareOf,
  ticks,
  toneOf,
} from "./model";

describe("the period and the filters an address names", () => {
  it("knows the four periods and falls back to 30 days for anything else", () => {
    expect(periodOf("7d")).toBe("7d");
    expect(periodOf("12m")).toBe("12m");
    expect(periodOf("yesterday")).toBe("30d");
    expect(periodOf(null)).toBe("30d");
    expect(periodOf(undefined)).toBe("30d");
  });

  it("reads the filters from an address and writes them back without the defaults", () => {
    const filters = filtersFromParams(new URLSearchParams("period=90d&location=HEAD&department=QA"));

    expect(filters).toEqual({ period: "90d", location: "HEAD", department: "QA" });
    expect(paramsFromFilters(filters)).toBe("period=90d&location=HEAD&department=QA");
    expect(paramsFromFilters(NO_FILTERS)).toBe("");
    expect(paramsFromFilters({ ...NO_FILTERS, department: "QA" })).toBe("department=QA");
  });

  it("is the same page again after a round trip through the address", () => {
    for (const filters of [
      NO_FILTERS,
      { period: "7d" as const, location: "", department: "" },
      { period: "30d" as const, location: "PLANT", department: "" },
      { period: "12m" as const, location: "PLANT", department: "QA" },
    ]) {
      expect(filtersFromParams(new URLSearchParams(paramsFromFilters(filters)))).toEqual(filters);
    }
  });

  it("says a choice is a filter only when it differs from what the page opens with", () => {
    expect(isFiltered(NO_FILTERS)).toBe(false);
    expect(isFiltered({ ...NO_FILTERS, period: "7d" })).toBe(true);
    expect(isFiltered({ ...NO_FILTERS, location: "PLANT" })).toBe(true);
  });

  it("copes with no address at all", () => {
    expect(filtersFromParams(null)).toEqual(NO_FILTERS);
  });
});

describe("how a number is written", () => {
  it("writes counts with separators, a missing one as a dash and a bound as at least", () => {
    expect(formatCount(1234)).toBe("1,234");
    expect(formatCount(0)).toBe("0");
    expect(formatCount(undefined)).toBe("–");
    expect(formatCount(50000, true)).toBe("at least 50,000");
  });

  it("writes days with one decimal and the singular for exactly one", () => {
    expect(formatDays(3)).toBe("3 days");
    expect(formatDays(3.46)).toBe("3.5 days");
    expect(formatDays(1)).toBe("1 day");
    expect(formatDays(undefined)).toBe("–");
  });

  it("writes percentages with at most one decimal", () => {
    expect(formatPercent(25)).toBe("25%");
    expect(formatPercent(33.3)).toBe("33.3%");
    expect(formatPercent(undefined)).toBe("–");
  });
});

describe("the change against the period before", () => {
  it("says up, with a percentage of the earlier number", () => {
    const change = describeChange(15, 12, "30d");

    expect(change.direction).toBe("up");
    expect(change.words).toBe("Up 25% on the 30 days before");
    expect(change.difference).toBe(3);
  });

  it("says down in words and not by colour alone", () => {
    const change = describeChange(9, 12, "7d");

    expect(change.direction).toBe("down");
    expect(change.words).toBe("Down 25% on the 7 days before");
  });

  it("says no change when the numbers are the same", () => {
    expect(describeChange(4, 4, "90d")).toMatchObject({ direction: "same", words: "No change on the 90 days before" });
  });

  it("writes a plain difference when the earlier number was zero, never a percentage of nothing", () => {
    expect(describeChange(5, 0, "30d").words).toBe("Up 5 on the 30 days before");
    expect(describeChange(0, 0, "30d").direction).toBe("same");
  });

  it("writes a difference of days and of points in their own unit", () => {
    expect(describeChange(4.5, 3, "30d", "days").words).toBe("Up 1.5 days on the 30 days before");
    expect(describeChange(10, 12.5, "30d", "points").words).toBe("Down 2.5 points on the 30 days before");
  });

  it("has nothing to say when either number is missing", () => {
    expect(describeChange(undefined, 3, "30d")).toEqual({ direction: "none", words: "", difference: null });
    expect(describeChange(3, undefined, "30d").words).toBe("");
  });

  it("calls a move good or bad only by what the tile counts", () => {
    expect(toneOf("up", "better")).toBe("good");
    expect(toneOf("up", "worse")).toBe("bad");
    expect(toneOf("down", "worse")).toBe("good");
    expect(toneOf("up", "neither")).toBe("neutral");
    expect(toneOf("same", "better")).toBe("neutral");
    expect(toneOf("none", "worse")).toBe("neutral");
  });
});

describe("days and months on an axis", () => {
  it("writes a day and a month the way people read them", () => {
    expect(dayLabel("2026-10-05")).toBe("5 Oct");
    expect(dayLabel("2026-01-31")).toBe("31 Jan");
    expect(monthLabel("2026-10")).toBe("Oct 26");
  });

  it("labels every day when there are few, and an even spread with the ends when there are many", () => {
    expect(axisIndexes(5, 6)).toEqual([0, 1, 2, 3, 4]);
    const spread = axisIndexes(30, 6);
    expect(spread).toHaveLength(6);
    expect(spread[0]).toBe(0);
    expect(spread[spread.length - 1]).toBe(29);
    expect(axisIndexes(0, 6)).toEqual([]);
  });
});

describe("the geometry of a graph", () => {
  it("rounds the top of an axis up to a round number", () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(12)).toBe(20);
    expect(niceMax(23)).toBe(25);
    expect(niceMax(41)).toBe(50);
    expect(niceMax(100)).toBe(100);
    expect(niceMax(101)).toBe(200);
  });

  it("holds every value under the top of its axis", () => {
    for (const value of [1, 3, 9, 10, 11, 49, 51, 999, 1001, 123456]) {
      expect(niceMax(value)).toBeGreaterThanOrEqual(value);
    }
  });

  it("gives evenly spaced ticks from zero to the top", () => {
    expect(ticks(20, 4)).toEqual([0, 5, 10, 15, 20]);
  });

  it("puts the largest value at the top of the box and zero at the bottom", () => {
    const points = linePoints([0, 5, 10], 200, 100, 10);

    expect(points).toEqual([
      { x: 0, y: 100 },
      { x: 100, y: 50 },
      { x: 200, y: 0 },
    ]);
  });

  it("centres a single point and never leaves the box for a negative value", () => {
    expect(linePoints([4], 200, 100, 4)).toEqual([{ x: 100, y: 0 }]);
    expect(linePoints([-3], 200, 100, 4)[0]?.y).toBe(100);
  });

  it("draws the line and closes the area along the bottom", () => {
    const points = linePoints([0, 10], 100, 50, 10);

    expect(linePath(points)).toBe("M0 50 L100 0");
    expect(areaPath(points, 50)).toBe("M0 50 L100 0 L100 50 L0 50 Z");
    expect(areaPath([], 50)).toBe("");
  });

  it("divides a ring by the shares of its parts and gives an empty ring no arcs", () => {
    const arcs = ringArcs([1, 1, 2]);

    expect(arcs.map((arc) => arc.share)).toEqual([0.25, 0.25, 0.5]);
    expect(arcs.map((arc) => arc.start)).toEqual([0, 0.25, 0.5]);
    expect(ringArcs([0, 0]).every((arc) => arc.share === 0)).toBe(true);
  });

  it("scales bars to the longest and draws a zero as zero", () => {
    expect(barPercents([2, 4, 0])).toEqual([50, 100, 0]);
    expect(barPercents([0, 0])).toEqual([0, 0]);
    expect(barPercents([])).toEqual([]);
  });

  it("works out a share as a whole percent", () => {
    expect(shareOf(1, 3)).toBe(33);
    expect(shareOf(0, 0)).toBe(0);
  });

  it("wraps the series colours after six", () => {
    expect([0, 1, 5, 6, 7].map(seriesNumber)).toEqual([1, 2, 6, 1, 2]);
  });
});
