import { describe, expect, it } from "vitest";
import {
  filtersFromParams,
  formatCount,
  formatScore,
  isFiltered,
  legendWords,
  managementLinks,
  markOf,
  monthName,
  monthShort,
  monthX,
  NO_FILTERS,
  paramsFromFilters,
  runsOf,
  scaleShare,
  scoreY,
  seriesOf,
} from "./model";

const params = (text: string) => new URLSearchParams(text);

describe("the filters of the page that compares the plants", () => {
  it("reads only what the address can hold, and takes anything else for no filter", () => {
    expect(filtersFromParams(params(""))).toEqual(NO_FILTERS);
    expect(filtersFromParams(params("month=2026-09&span=12&plant=PL1&form=11111111-1111-1111-1111-111111111111"))).toEqual({
      month: "2026-09",
      span: 12,
      plant: "PL1",
      form: "11111111-1111-1111-1111-111111111111",
    });
    expect(filtersFromParams(params("month=last&span=3")).month).toBe("last");
    expect(filtersFromParams(params("month=2026-13&span=5&plant=<script>&form=nope"))).toEqual(NO_FILTERS);
  });

  it("writes only what differs from the default, so the address stays short and shareable", () => {
    expect(paramsFromFilters(NO_FILTERS)).toBe("");
    expect(paramsFromFilters({ ...NO_FILTERS, month: "last", plant: "PL1" })).toBe("month=last&plant=PL1");
    expect(paramsFromFilters({ ...NO_FILTERS, span: 3 })).toBe("span=3");
    expect(isFiltered(NO_FILTERS)).toBe(false);
    expect(isFiltered({ ...NO_FILTERS, span: 12 })).toBe(true);
  });
});

describe("where a number leads", () => {
  const links = managementLinks("management-core");

  it("opens the list a tile counts, already narrowed, in the application it is in", () => {
    expect(links.sent("2026-11", "")).toBe("/apps/management-core/s-fill-ins?period=2026-11&sent=1&plan=none");
    expect(links.needs("2026-11", "PL1")).toBe("/apps/management-core/s-fill-ins?period=2026-11&plant=PL1&needs=1");
    expect(links.waiting("review", "")).toBe("/apps/management-core/s-fill-ins?waiting=review");
    expect(links.waiting("top", "PL2")).toBe("/apps/management-core/s-fill-ins?waiting=top&plant=PL2");
    expect(links.plansOpen("")).toBe("/apps/management-core/s-fill-ins?plan=open");
    expect(links.scored("2026-11", "")).toContain("scored=1");
  });

  it("puts no limit or score value in an address", () => {
    const all = [
      links.sent("2026-11", "PL1"),
      links.scored("2026-11", "PL1"),
      links.needs("2026-11", "PL1"),
      links.waiting("top", "PL1"),
      links.plansOpen("PL1"),
    ].join(" ");
    expect(all).not.toMatch(/limit|score=/);
  });
});

describe("the words", () => {
  it("names months and writes scores without trailing zeros", () => {
    expect(monthName("2026-11")).toBe("Nov 2026");
    expect(monthShort("2026-01")).toBe("Jan");
    expect(monthName("garbage")).toBe("garbage");
    expect(formatScore(86.5)).toBe("86.5%");
    expect(formatScore(90)).toBe("90%");
    expect(formatScore(86.456)).toBe("86.46%");
    expect(formatScore(undefined)).toBe("—");
    expect(formatCount(1234, true)).toBe("at least 1,234");
    expect(formatCount(undefined)).toBe("—");
  });

  it("says the legend in the numbers the server sent, and nothing where the application declares none", () => {
    expect(legendWords({ limit: 85, excellentAbove: 85, goodAbove: 65 })).toBe(
      "Excellent: above 85%. Good: above 65% up to 85%. Poor: 65% or less. An Action plan is needed below 85%.",
    );
    expect(legendWords({})).toBeUndefined();
    expect(legendWords({ excellentAbove: 90, goodAbove: 70 })).toBe("Excellent: above 90%. Good: above 70% up to 90%. Poor: 70% or less.");
  });
});

describe("drawing", () => {
  it("breaks a line at a month with no score: a gap, never a point at zero", () => {
    const runs = runsOf([{ score: 80 }, { score: 82 }, { score: null }, { score: undefined }, { score: 70 }]);
    expect(runs.map((run) => run.map((entry) => entry.index))).toEqual([[0, 1], [4]]);
    expect(runsOf([{ score: null }])).toEqual([]);
    expect(runsOf([{ score: 0 }])).toHaveLength(1);
  });

  it("keeps a score on a 0 to 100 scale and the months along the width", () => {
    expect(scaleShare(120)).toBe(100);
    expect(scaleShare(-5)).toBe(0);
    expect(scoreY(100, 200)).toBe(0);
    expect(scoreY(0, 200)).toBe(200);
    expect(scoreY(50, 200)).toBe(100);
    expect(monthX(0, 6, 500)).toBe(0);
    expect(monthX(5, 6, 500)).toBe(500);
    expect(monthX(0, 1, 500)).toBe(250);
  });

  it("tells series apart by shape and colour in turn", () => {
    expect(markOf(0)).not.toBe(markOf(1));
    expect(markOf(6)).toBe(markOf(0));
    expect(seriesOf(0)).toBe(1);
    expect(seriesOf(6)).toBe(1);
  });
});
