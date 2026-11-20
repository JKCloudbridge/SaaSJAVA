import { describe, expect, it } from "vitest";
import {
  bridgePath,
  bridges,
  changeText,
  columnLabel,
  dayText,
  filterPlans,
  fitScale,
  monthAbbreviation,
  nearestMonth,
  runsOfScores,
  scoreText,
  seriesVar,
  shortName,
  spreadLabels,
  statusClass,
  type BoardPlan,
  type BoardPlant,
} from "./model";

function plant(change: number | undefined, changeMonth: string | undefined): BoardPlant {
  return {
    plant: "P1",
    label: "Plant 1",
    colour: 1,
    points: [],
    scoresSubmitted: 0,
    scoresOf: 0,
    plansSent: 0,
    plansSubmitted: 0,
    overdue: 0,
    change,
    changeMonth,
  };
}

describe("the words and days of the plant board", () => {
  it("writes a day as day and month, with the year only where it is not this year, and a dash for none", () => {
    expect(dayText("2026-07-06", "2026-11-20")).toBe("6 Jul");
    expect(dayText("2025-12-30", "2026-11-20")).toBe("30 Dec 2025");
    expect(dayText(undefined, "2026-11-20")).toBe("–");
    expect(dayText("2026-02-30x", "2026-11-20")).toBe("–");
  });

  it("names the months and puts the year on the first column and where the year changes", () => {
    const months = ["2025-11", "2025-12", "2026-01", "2026-02"];
    expect(monthAbbreviation("2026-04")).toBe("Apr");
    expect(monthAbbreviation("garbage")).toBe("garbage");
    expect(columnLabel(months, 0)).toBe("Nov 2025");
    expect(columnLabel(months, 1)).toBe("Dec");
    expect(columnLabel(months, 2)).toBe("Jan 2026");
    expect(columnLabel(months, 3)).toBe("Feb");
  });

  it("writes a score whole when it is whole, with two decimals at most, and a dash for a gap", () => {
    expect(scoreText(91)).toBe("91");
    expect(scoreText(86.5)).toBe("86.5");
    expect(scoreText(86.456)).toBe("86.46");
    expect(scoreText(null)).toBe("–");
    expect(scoreText(undefined)).toBe("–");
  });

  it("shortens a plant name for the end of a line", () => {
    expect(shortName("Plant 1")).toBe("P1");
    expect(shortName("Plant 12")).toBe("P12");
    expect(shortName("Northern works")).toBe("Nort");
  });

  it("says how a score moved against the earlier month that has one, and nothing without such a month", () => {
    expect(changeText(plant(1, "2026-09"))).toEqual({ arrow: "up", text: "1 vs Sep" });
    expect(changeText(plant(-2.5, "2026-08"))).toEqual({ arrow: "down", text: "2.5 vs Aug" });
    expect(changeText(plant(0, "2026-08"))).toEqual({ arrow: "same", text: "0 vs Aug" });
    expect(changeText(plant(undefined, undefined))).toBeUndefined();
    expect(changeText(plant(3, undefined))).toBeUndefined();
  });

  it("gives a plant a colour place that every widget uses, and starts again after the sixth", () => {
    expect(seriesVar(1)).toBe("var(--series-1)");
    expect(seriesVar(3)).toBe("var(--series-3)");
    expect(seriesVar(7)).toBe("var(--series-1)");
    expect(seriesVar(0)).toBe("var(--series-1)");
  });
});

describe("the scale and the lines", () => {
  it("fits the data and is not forced to start at zero", () => {
    expect(fitScale([79, 91, 86, null])).toEqual({ low: 75, high: 95, ticks: [75, 80, 85, 90, 95] });
    const flat = fitScale([88, 88]);
    expect(flat.low).toBeLessThan(88);
    expect(flat.high).toBeGreaterThan(88);
    expect(fitScale([null, undefined])).toEqual({ low: 0, high: 100, ticks: [0, 50, 100] });
  });

  it("breaks a line at a month with no score and joins the two sides with one thin piece", () => {
    const runs = runsOfScores([82, 84, null, 86, null, null, 88]);
    expect(runs).toEqual([[0, 1], [3], [6]]);
    expect(bridges(runs)).toEqual([
      [1, 3],
      [3, 6],
    ]);
    expect(bridges([[0, 1]])).toEqual([]);
    expect(runsOfScores([null])).toEqual([]);
    expect(bridgePath({ x: 1, y: 2 }, { x: 3, y: 4 })).toBe("M1,2 L3,4");
  });

  it("moves end labels apart so two never overlap, keeping their order", () => {
    expect(spreadLabels([100, 102, 101], 13)).toEqual([100, 126, 113]);
    expect(spreadLabels([10, 50], 13)).toEqual([10, 50]);
    expect(spreadLabels([], 13)).toEqual([]);
  });

  it("finds the month nearest to a place along the plot", () => {
    expect(nearestMonth(0, 10)).toBe(0);
    expect(nearestMonth(1, 10)).toBe(9);
    expect(nearestMonth(0.5, 10)).toBe(5);
    expect(nearestMonth(-3, 10)).toBe(0);
    expect(nearestMonth(7, 10)).toBe(9);
    expect(nearestMonth(0.4, 1)).toBe(0);
  });
});

describe("the tracker", () => {
  const plan = (status: string): BoardPlan => ({
    plant: "P1",
    label: "Plant 1",
    colour: 1,
    month: "2026-03",
    status,
    statusText: status,
    fillId: status,
  });
  const plans = ["OVERDUE", "AWAITING", "SUBMITTED", "LATE", "NO_DATE"].map(plan);

  it("filters the detail table by chip, and Submitted holds the plans sent late", () => {
    expect(filterPlans(plans, "all")).toHaveLength(5);
    expect(filterPlans(plans, "overdue").map((one) => one.status)).toEqual(["OVERDUE"]);
    expect(filterPlans(plans, "submitted").map((one) => one.status)).toEqual(["SUBMITTED", "LATE"]);
  });

  it("gives one class to a status, for a cell, a pill and the key alike", () => {
    expect(statusClass("SUBMITTED")).toBe("done");
    expect(statusClass("LATE")).toBe("late");
    expect(statusClass("OVERDUE")).toBe("over");
    expect(statusClass("AWAITING")).toBe("wait");
    expect(statusClass("NO_SCORE")).toBe("none");
    expect(statusClass("IN_REVIEW")).toBe("tbd");
    expect(statusClass("NOT_SENT")).toBe("tbd");
    expect(statusClass("NO_PLAN")).toBe("ok");
  });
});
