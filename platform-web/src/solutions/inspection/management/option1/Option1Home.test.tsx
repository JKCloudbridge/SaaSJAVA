import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fakeRouter, setUrl } from "@/test/navigation";
import { fakeApi, json, type Call } from "@/test/fakeApi";
import { Option1Home } from "./Option1Home";
import type { BoardCell, BoardPlan, BoardPlant, PlantBoard } from "./model";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("../../application", () => ({ useApplication: () => "management-core", useApplicationLabel: () => "Management Core" }));

const MONTHS = ["2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"];
const P1 = [82, 84, 83, 86, 87, 86, 88, 89, 90, 91];
const P2 = [88, 86, null, 84, 87, 83, 86, 88, 85, 87];
const P3 = [79, 81, 84, 86, null, 88, 85, null, 87, 89];

function cells(statuses: Record<number, [string, string]>, scores: (number | null)[]): BoardCell[] {
  return MONTHS.map((month, index) => {
    const score = scores[index];
    const found = statuses[index];
    const [status, word] = found ?? (score === null ? ["NO_SCORE", "No score"] : ["NO_PLAN", "Passed"]);
    return { month, status, word, ...(score === null || score === undefined ? {} : { score }), fillId: `fill-${month}` };
  });
}

function tile(plant: string, label: string, colour: number, points: (number | null)[], more: Partial<BoardPlant>): BoardPlant {
  return {
    plant,
    label,
    colour,
    latestMonth: "2026-10",
    points: points.map((score) => (score === null ? undefined : score)) as number[],
    scoresSubmitted: points.filter((score) => score !== null).length,
    scoresOf: 10,
    plansSent: 0,
    plansSubmitted: 0,
    overdue: 0,
    ...more,
  };
}

function plan(plant: string, label: string, colour: number, month: string, status: string, statusText: string, score: number, more: Partial<BoardPlan> = {}): BoardPlan {
  return { plant, label, colour, month, score, status, statusText, fillId: `fill-${plant}-${month}`, ...more };
}

function board(changes: Partial<PlantBoard> = {}): PlantBoard {
  return {
    generatedAt: "2026-11-20T09:30:00Z",
    month: "2026-11",
    today: "2026-11-20",
    limit: 85,
    dueDays: 10,
    months: MONTHS,
    currentMonth: "2026-11",
    monthChoices: ["2026-11", "2026-10", "2026-09"],
    filters: {
      plants: [
        { code: "P1", label: "Plant 1" },
        { code: "P2", label: "Plant 2" },
        { code: "P3", label: "Plant 3" },
      ],
      forms: [],
    },
    applied: {},
    atLeast: false,
    plants: [
      tile("P1", "Plant 1", 1, P1, { latestScore: 91, change: 1, changeMonth: "2026-09", plansSent: 3, plansSubmitted: 3 }),
      tile("P2", "Plant 2", 2, P2, { latestScore: 87, change: 2, changeMonth: "2026-09", plansSent: 2, plansSubmitted: 1, overdue: 1 }),
      tile("P3", "Plant 3", 3, P3, { latestScore: 89, change: 2, changeMonth: "2026-09", plansSent: 3, plansSubmitted: 2, overdue: 1 }),
    ],
    grid: [
      {
        plant: "P1",
        label: "Plant 1",
        colour: 1,
        cells: cells({ 0: ["SUBMITTED", "Submitted"], 1: ["SUBMITTED", "Submitted"], 2: ["SUBMITTED", "Submitted"] }, P1),
        current: { month: "2026-11", status: "IN_REVIEW", word: "In review" },
      },
      {
        plant: "P2",
        label: "Plant 2",
        colour: 2,
        cells: cells({ 3: ["SUBMITTED", "Submitted"], 5: ["OVERDUE", "Overdue"] }, P2),
        current: { month: "2026-11", status: "IN_REVIEW", word: "In review" },
      },
      {
        plant: "P3",
        label: "Plant 3",
        colour: 3,
        cells: cells({ 0: ["LATE", "Late"], 1: ["SUBMITTED", "Submitted"], 2: ["OVERDUE", "Overdue"] }, P3),
        current: { month: "2026-11", status: "NOT_SENT", word: "Awaiting" },
      },
    ],
    plans: [
      plan("P2", "Plant 2", 2, "2026-06", "OVERDUE", "Overdue 127 days", 83, { sentOn: "2026-07-06", dueOn: "2026-07-16", overdueDays: 127 }),
      plan("P3", "Plant 3", 3, "2026-03", "OVERDUE", "Overdue 218 days", 84, { sentOn: "2026-04-06", dueOn: "2026-04-16", overdueDays: 218 }),
      plan("P2", "Plant 2", 2, "2026-04", "SUBMITTED", "Submitted", 84, { sentOn: "2026-05-06", dueOn: "2026-05-16", submittedOn: "2026-05-15" }),
      plan("P3", "Plant 3", 3, "2026-01", "LATE", "Submitted late", 79, { sentOn: "2026-02-05", dueOn: "2026-02-15", submittedOn: "2026-02-20" }),
    ],
    totals: { plansSent: 8, plansSubmitted: 6, overdue: 2 },
    kpis: { avgDaysToSubmit: 7.5, avgDaysOf: 6, onTimePercent: 83, onTimeCount: 5, reportingSent: 1, reportingOf: 3, reportingMonth: "2026-11", streakMonths: 4, streakPlant: "Plant 1" },
    problems: [],
    ...changes,
  };
}

let calls: Call[];

function open(address = "/apps/management-core/s-home", answer: PlantBoard = board()) {
  calls = fakeApi({ "POST /api/v1/management-home/plant-board": () => json({ data: answer }) });
  setUrl(address);
  return render(<Option1Home />);
}

beforeEach(() => {
  fakeRouter.push.mockClear();
});

describe("Option 1 of the Management Core home: the plant board", () => {
  it("asks the server once, for a year of months by default", async () => {
    open();

    await screen.findByTestId("board-tiles");

    const asked = calls.filter((call) => call.key === "POST /api/v1/management-home/plant-board");
    expect(asked).toHaveLength(1);
    expect(JSON.parse(asked[0]!.body)).toEqual({ month: "this", span: 12 });
  });

  it("draws a tile for each plant with the latest score, how it moved, and the four facts", async () => {
    open();
    await screen.findByTestId("board-tiles");

    expect(screen.getByTestId("score-P1")).toHaveTextContent("91 / 100");
    expect(screen.getByTestId("change-P1")).toHaveTextContent("▲ 1 vs Sep");
    const first = screen.getByTestId("tile-P1");
    expect(within(first).getByText("Scores submitted").nextSibling).toHaveTextContent("10 of 10");
    expect(within(first).getByText("Action plans sent").nextSibling).toHaveTextContent("3");
    expect(within(first).getByText("Submitted by plant head").nextSibling).toHaveTextContent("3 of 3");
    expect(screen.getByTestId("overdue-P1")).toHaveTextContent("0");
    expect(screen.getByTestId("overdue-P1")).not.toHaveClass("pb-bad");
    expect(screen.getByTestId("overdue-P2")).toHaveClass("pb-bad");
    expect(within(screen.getByTestId("tile-P3")).getByText("Scores submitted").nextSibling).toHaveTextContent("8 of 10");
  });

  it("draws a dash and no change for a plant whose latest month has no score", async () => {
    const base = board();
    open("/apps/management-core/s-home", {
      ...base,
      plants: base.plants!.map((one, index) => (index === 0 ? { ...one, latestScore: undefined, change: undefined, changeMonth: undefined } : one)),
    });
    await screen.findByTestId("board-tiles");

    expect(screen.getByTestId("score-P1")).toHaveTextContent("– / 100");
    expect(screen.queryByTestId("change-P1")).toBeNull();
  });

  it("shows the bar graph first and offers the line and the table, never the view that is shown", async () => {
    open();
    const chart = await screen.findByTestId("board-chart");

    expect(within(chart).getByTestId("bars-P1")).toBeInTheDocument();
    expect(within(chart).queryByTestId("line-P1")).toBeNull();
    expect(within(chart).queryByRole("button", { name: /view as bar graph/i })).toBeNull();
    expect(within(chart).getByRole("button", { name: /view as line/i })).toBeInTheDocument();
    expect(within(chart).getByRole("button", { name: /view as table/i })).toBeInTheDocument();

    fireEvent.click(within(chart).getByRole("button", { name: /view as line/i }));
    expect(within(chart).getByTestId("line-P1")).toBeInTheDocument();
    expect(within(chart).queryByRole("button", { name: /view as line/i })).toBeNull();
    expect(within(chart).getByRole("button", { name: /view as bar graph/i })).toBeInTheDocument();

    fireEvent.click(within(chart).getByRole("button", { name: /view as table/i }));
    expect(within(chart).getByRole("table")).toBeInTheDocument();
    expect(within(chart).queryByRole("button", { name: /view as table/i })).toBeNull();
    fireEvent.click(within(chart).getByRole("button", { name: /view as bar graph/i }));
    expect(within(chart).getByTestId("bars-P1")).toBeInTheDocument();
  });

  it("draws a bar for each score on a scale from 0 to 100 in steps of 10, a cross for each month with none, and no bar of zero height", async () => {
    open();
    const chart = await screen.findByTestId("board-chart");

    expect(within(chart).getAllByTestId("bar-P1")).toHaveLength(10);
    expect(within(chart).getAllByTestId("bar-P2")).toHaveLength(9);
    expect(within(chart).getAllByTestId("bar-P3")).toHaveLength(8);
    expect(within(chart).getAllByTestId("cross-P2")).toHaveLength(1);
    expect(within(chart).getAllByTestId("cross-P3")).toHaveLength(2);
    const ticks = Array.from(chart.querySelectorAll("text.pb-axis")).map((tick) => tick.textContent);
    expect(ticks).toEqual(expect.arrayContaining(["0", "10", "50", "90", "100"]));
    // A bar's height is its score on the 0 to 100 scale: 91 is taller than 83, and a score of 100 would fill the plot.
    const heights = (plant: string) => within(chart).getAllByTestId(`bar-${plant}`).map((bar) => Number(bar.getAttribute("height")));
    expect(heights("P1")[9]!).toBeGreaterThan(heights("P1")[2]!);
    expect(Math.round(heights("P1")[9]! / heights("P1")[0]! * 100)).toBe(Math.round((91 / 82) * 100));
  });

  it("opens the same tip over a bar as over a dot, with Missed for a month with no score", async () => {
    open();
    const chart = await screen.findByTestId("board-chart");

    fireEvent.pointerEnter(within(chart).getAllByTestId("bar-P1")[2]!.parentElement!);

    expect(screen.getByTestId("board-tip")).toHaveTextContent("Mar");
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Plant 2 - Missed");
    fireEvent.pointerLeave(within(chart).getAllByTestId("bar-P1")[2]!.parentElement!);
    expect(screen.queryByTestId("board-tip")).toBeNull();
    const area = screen.getByTestId("board-chart-area");
    fireEvent.keyDown(area, { key: "Home" });
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Jan");
  });

  it("draws one line with dots for each plant, a cross for each missing month, a dotted piece across each gap and no limit line", async () => {
    const { container } = open();
    const chart = await screen.findByTestId("board-chart");
    fireEvent.click(within(chart).getByRole("button", { name: /view as line/i }));

    expect(within(chart).getByTestId("line-P1").querySelectorAll("circle.pb-point")).toHaveLength(10);
    expect(within(chart).queryAllByTestId("cross-P1")).toHaveLength(0);
    expect(within(chart).queryAllByTestId("cross-P2")).toHaveLength(1);
    expect(within(chart).queryAllByTestId("cross-P3")).toHaveLength(2);
    expect(within(chart).queryAllByTestId("bridge-P1")).toHaveLength(0);
    expect(within(chart).queryAllByTestId("bridge-P2")).toHaveLength(1);
    expect(within(chart).queryAllByTestId("bridge-P3")).toHaveLength(2);
    // The end of a line carries no label (2026-11-20): the readout and the table give the numbers.
    expect(chart).not.toHaveTextContent("P1 91");
    expect(chart).toHaveTextContent("Score not submitted");
    // No reference line for the limit and no words about it.
    expect(container.querySelector(".score-limit-line")).toBeNull();
    expect(container.textContent).not.toMatch(/Below 85|action plan\b.*\bBelow|limit/i);
  });

  it("shows a tip beside a dot while it is hovered and removes it when the pointer leaves, with no highlight or line of numbers under the chart", async () => {
    open();
    await screen.findByTestId("board-chart");
    fireEvent.click(screen.getByRole("button", { name: /view as line/i }));
    const area = screen.getByTestId("board-chart-area");

    expect(screen.queryByTestId("board-tip")).toBeNull();
    expect(screen.queryByTestId("board-readout")).toBeNull();
    expect(screen.queryByTestId("board-selected")).toBeNull();
    // Hovering a dot opens the tip of its month; leaving closes it.
    const dot = screen.getByTestId("line-P1").querySelectorAll("g")[2]!;
    fireEvent.pointerEnter(dot);
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Mar");
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Plant 2 - Missed");
    fireEvent.pointerLeave(dot);
    expect(screen.queryByTestId("board-tip")).toBeNull();
    area.focus();
    fireEvent.keyDown(area, { key: "ArrowLeft" });
    const tip = screen.getByTestId("board-tip");
    expect(tip).toHaveTextContent("Sep");
    expect(tip).toHaveTextContent("Plant 1 90");
    expect(tip).toHaveTextContent("Plant 2 85");
    fireEvent.keyDown(area, { key: "Home" });
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Jan");
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Plant 3 79");
    fireEvent.keyDown(area, { key: "ArrowLeft" });
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Jan");
    fireEvent.keyDown(area, { key: "End" });
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Oct");
    fireEvent.keyDown(area, { key: "ArrowRight" });
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Oct");
    // A month a plant did not report says Missed and is never a zero.
    fireEvent.keyDown(area, { key: "Home" });
    fireEvent.keyDown(area, { key: "ArrowRight" });
    fireEvent.keyDown(area, { key: "ArrowRight" });
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Mar");
    expect(screen.getByTestId("board-tip")).toHaveTextContent("Plant 2 - Missed");
    fireEvent.keyDown(area, { key: "Escape" });
    expect(screen.queryByTestId("board-tip")).toBeNull();
  });

  it("offers the chart as a table of the very same numbers", async () => {
    open();
    const chart = await screen.findByTestId("board-chart");

    fireEvent.click(within(chart).getByRole("button", { name: /view as table/i }));

    const table = within(chart).getByRole("table");
    expect(table).toHaveTextContent("Plant 2");
    expect(within(table).getAllByText("No score").length).toBeGreaterThan(0);
  });

  it("shows the tracker's three numbers, the grid with a word under each score, and the key", async () => {
    open();
    const tracker = await screen.findByTestId("board-tracker");

    expect(within(tracker).getByTestId("stat-sent")).toHaveTextContent("8Plans sent");
    expect(within(tracker).getByTestId("stat-submitted")).toHaveTextContent("6Submitted by plant head");
    expect(within(tracker).getByTestId("stat-overdue")).toHaveTextContent("2Overdue");
    const grid = within(tracker).getByTestId("board-grid");
    const rows = within(grid).getAllByRole("row");
    expect(rows).toHaveLength(4);
    const plant2 = within(rows[2]!);
    expect(plant2.getByText("Overdue")).toBeInTheDocument();
    expect(plant2.getAllByText("Passed").length).toBeGreaterThan(0);
    expect(plant2.getByText("No score")).toBeInTheDocument();
    expect(plant2.getByText("In review")).toBeInTheDocument();
    expect(within(rows[3]!).getByText("Awaiting")).toBeInTheDocument();
    expect(within(rows[3]!).getByText("Late")).toBeInTheDocument();
    const key = within(tracker).getByTestId("board-key");
    expect(key).toHaveTextContent("Score 85 or above, passed");
    for (const words of ["Plan submitted on time", "Plan submitted late", "Plan sent, awaiting plant head", "Plan overdue", "Score not submitted"]) {
      expect(key).toHaveTextContent(words);
    }
  });

  it("lists the plans with their days and status and filters them by chip", async () => {
    open();
    const tracker = await screen.findByTestId("board-tracker");
    const table = within(tracker).getByTestId("board-plans");

    expect(within(table).getAllByRole("row")).toHaveLength(5);
    expect(table).toHaveTextContent("Overdue 127 days");
    expect(table).toHaveTextContent("6 Jul");
    expect(table).toHaveTextContent("16 Jul");
    expect(table).toHaveTextContent("Not yet");
    expect(table).toHaveTextContent("15 May");

    fireEvent.click(within(tracker).getByRole("button", { name: "Overdue" }));
    expect(within(within(tracker).getByTestId("board-plans")).getAllByRole("row")).toHaveLength(3);
    fireEvent.click(within(tracker).getByRole("button", { name: "Submitted" }));
    const submitted = within(tracker).getByTestId("board-plans");
    expect(submitted).toHaveTextContent("Submitted late");
    expect(submitted).not.toHaveTextContent("Overdue");
    expect(within(tracker).getByRole("button", { name: "Submitted" })).toHaveAttribute("aria-pressed", "true");
  });

  it("shows four key numbers of equal size in one line, and a dash where a number has nothing to be made of", async () => {
    open();
    const band = await screen.findByTestId("board-kpis");

    expect(within(band).getAllByRole("listitem")).toHaveLength(4);
    expect(screen.getByTestId("kpi-avg-days")).toHaveTextContent("7.5");
    expect(screen.getByTestId("kpi-avg-days")).toHaveTextContent("6 plans");
    expect(screen.getByTestId("kpi-on-time")).toHaveTextContent("83%");
    expect(screen.getByTestId("kpi-on-time")).toHaveTextContent("5 of 6");
    expect(screen.getByTestId("kpi-reporting")).toHaveTextContent("Plants reported, Nov");
    expect(screen.getByTestId("kpi-reporting")).toHaveTextContent("1 of 3");
    expect(screen.getByTestId("kpi-streak")).toHaveTextContent("4");
    expect(screen.getByTestId("kpi-streak")).toHaveTextContent("Plant 1");
  });

  it("draws a dash for the average and the on-time share when no plan has both days", async () => {
    open("/apps/management-core/s-home", board({ kpis: { avgDaysOf: 0, onTimeCount: 0, reportingSent: 0, reportingOf: 3, streakMonths: 0 } }));
    await screen.findByTestId("board-kpis");

    expect(screen.getByTestId("kpi-avg-days")).toHaveTextContent("–");
    expect(screen.getByTestId("kpi-on-time")).toHaveTextContent("–");
    expect(screen.getByTestId("kpi-streak")).toHaveTextContent("0");
  });

  it("lists the fill-ins that wait for a reviewer, longest first, with no reviewer named", async () => {
    open("/apps/management-core/s-home", board({
      pending: [
        { plant: "P1", label: "Plant 1", colour: 1, month: "2026-10", name: "Plant activity", sentOn: "2026-11-10", daysWaiting: 10, actionPlan: false, fillId: "w1" },
        { plant: "P2", label: "Plant 2", colour: 2, month: "2026-11", name: "Fix the door", score: 70, sentOn: "2026-11-19", daysWaiting: 1, actionPlan: true, fillId: "w2" },
      ],
    }));
    const section = await screen.findByTestId("board-pending");

    const rows = within(section).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("Plant 1");
    expect(rows[1]).toHaveTextContent("10 Nov");
    expect(rows[1]).toHaveTextContent("10");
    expect(rows[2]).toHaveTextContent("Fix the door (Action plan)");
    expect(within(rows[1]!).getByRole("link", { name: "Plant activity" })).toHaveAttribute("href", "/apps/management-core/s-fill-ins?open=w1");
  });

  it("says No data when nothing waits for a reviewer", async () => {
    open();
    const section = await screen.findByTestId("board-pending");

    expect(section).toHaveTextContent("No data");
  });

  it("opens the fill-in a cell is about, only where there is a score to open", async () => {
    open();
    const grid = await screen.findByTestId("board-grid");

    expect(within(grid).getAllByTestId("cell-2026-01")[0]).toHaveAttribute("href", "/apps/management-core/s-fill-ins?open=fill-2026-01");
    const gap = within(grid).getAllByTestId("cell-2026-03")[1]!;
    expect(gap.tagName).toBe("DIV");
  });

  it("sends the filters the address names and puts a choice in the address", async () => {
    open("/apps/management-core/s-home?month=2026-09&span=6&plant=P2");

    await screen.findByTestId("board-tiles");

    const body = JSON.parse(calls.find((call) => call.key === "POST /api/v1/management-home/plant-board")!.body);
    expect(body).toEqual({ month: "2026-09", span: 6, plant: "P2" });
    fireEvent.change(screen.getByLabelText("Plant"), { target: { value: "P1" } });
    await waitFor(() => expect(fakeRouter.push).toHaveBeenCalledWith("/apps/management-core/s-home?month=2026-09&span=6&plant=P1", { scroll: false }));
  });

  it("writes no explaining sentence anywhere on the board", async () => {
    const { container } = open();
    await screen.findByTestId("board-tracker");

    const text = container.textContent ?? "";
    for (const sentence of ["Hover or tap", "Each plan is due", "Scores below", "gets an action plan", "Months are months in UTC", "Showing"]) {
      expect(text).not.toContain(sentence);
    }
  });

  it("draws a section the person may not read as nothing, and a failed one as a failure, never a zero", async () => {
    open("/apps/management-core/s-home", board({ plants: undefined, grid: undefined, plans: undefined, totals: undefined, problems: [] }));
    expect(await screen.findByText("No data")).toBeInTheDocument();
    expect(screen.queryByTestId("board-tiles")).toBeNull();
    expect(screen.queryByTestId("board-tracker")).toBeNull();
  });

  it("names a section that failed", async () => {
    open("/apps/management-core/s-home", board({ plants: undefined, grid: undefined, plans: undefined, totals: undefined, problems: ["fill-ins"] }));

    expect(await screen.findByTestId("board-failed")).toHaveTextContent("could not be worked out");
  });

  it("says No data instead of drawing an empty chart or an empty table", async () => {
    open("/apps/management-core/s-home", board({ months: [], grid: [], plans: [], plants: [] }));

    const chart = await screen.findByTestId("board-chart");
    expect(chart).toHaveTextContent("No data");
    expect(screen.getByTestId("board-tracker")).toHaveTextContent("No data");
  });

  it("shows the API's words when the page cannot be had, with a way to try again", async () => {
    calls = fakeApi({
      "POST /api/v1/management-home/plant-board": () =>
        json({ error: { code: "NOT_FOUND", message: "This application does not exist.", requestId: "r1" } }, 404),
    });
    setUrl("/apps/management-core/s-home");
    render(<Option1Home />);

    expect(await screen.findByTestId("home-failed")).toHaveTextContent("This application does not exist.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("draws every colour as a token and no pattern or hatch", async () => {
    const { container } = open();
    await screen.findByTestId("board-chart");

    expect(container.querySelector("pattern")).toBeNull();
    container.querySelectorAll<SVGElement>("circle:not(.pb-hit), .pb-line").forEach((mark) => {
      expect(mark.getAttribute("fill") ?? mark.getAttribute("stroke") ?? "").toMatch(/var\(--plant-\d\)|none/);
    });
  });
});
