import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeRouter, setUrl } from "@/test/navigation";
import { fakeApi, json, type Call } from "@/test/fakeApi";
import type { ManagementSummary } from "./model";
import { ManagementHomePage } from "./ManagementHome";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("../application", () => ({ useApplication: () => "management-core", useApplicationLabel: () => "Management Core" }));

const MONTHS = ["2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11"];

function summary(changes: Partial<ManagementSummary> = {}): ManagementSummary {
  return {
    generatedAt: "2026-11-15T09:30:00Z",
    month: "2026-11",
    previousMonth: "2026-10",
    months: MONTHS,
    monthChoices: ["2026-11", "2026-10", "2026-09", "2026-08"],
    filters: {
      plants: [
        { code: "P1", label: "Plant 1" },
        { code: "P2", label: "Plant 2" },
        { code: "P3", label: "Plant 3" },
      ],
      forms: [{ id: "11111111-1111-1111-1111-111111111111", name: "Plant activity" }],
    },
    applied: {},
    legend: { limit: 85, excellentAbove: 85, goodAbove: 65 },
    tiles: {
      plantsSubmitted: { value: 2, previous: 3, whole: 3, atLeast: false },
      averageScore: { value: 84.25, previous: 80, atLeast: false },
      plantsBelowLimit: { value: 1, previous: 0, extra: 1, atLeast: false },
      waitingForReview: { value: 4, atLeast: false },
      actionPlansOpen: { value: 3, atLeast: false },
    },
    charts: {
      scoresByPlant: [
        { plant: "P1", label: "Plant 1", score: 92.5, forms: 1, rank: 1, band: "EXCELLENT", bandWord: "Excellent", needsActionPlan: false, belowCurrentLimit: false },
        { plant: "P2", label: "Plant 2", score: 76, forms: 1, rank: 2, band: "GOOD", bandWord: "Good", needsActionPlan: true, belowCurrentLimit: true },
        { plant: "P3", label: "Plant 3", forms: 0, needsActionPlan: false, belowCurrentLimit: false },
      ],
      plantsOverMonths: [
        {
          plant: "P1",
          label: "Plant 1",
          points: MONTHS.map((month, index) => (index === 2 ? { month } : { month, score: 80 + index })),
        },
        { plant: "P2", label: "Plant 2", points: MONTHS.map((month, index) => (index < 4 ? { month } : { month, score: 70 })) },
      ],
      averageByMonth: MONTHS.map((month, index) => (index === 0 ? { month, plants: 0 } : { month, average: 80 + index, plants: 2 })),
      actionPlansByPlant: [
        { plant: "P2", label: "Plant 2", open: 1, sent: 1, approved: 1, owed: 0 },
        { plant: "P3", label: "Plant 3", open: 0, sent: 0, approved: 0, owed: 2 },
      ],
      fillsByMonth: MONTHS.map((month, index) => ({ month, draft: 0, submitted: index === 5 ? 2 : 0, returned: 0, approved: index === 5 ? 1 : 2 })),
    },
    problems: [],
    ...changes,
  };
}

let calls: Call[];

function open(address = "/apps/management-core/s-home", answer: ManagementSummary = summary()) {
  calls = fakeApi({ "POST /api/v1/management-home/summary": () => json({ data: answer }) });
  setUrl(address);
  return render(<ManagementHomePage />);
}

beforeEach(() => {
  fakeRouter.push.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("the home page of Management Core", () => {
  it("shows the five numbers with their words and asks the server once", async () => {
    open();

    expect(await screen.findByTestId("value-plants-sent")).toHaveTextContent("2 of 3");
    expect(screen.getByTestId("value-average-score")).toHaveTextContent("84.25%");
    expect(screen.getByTestId("value-below-limit")).toHaveTextContent("1");
    expect(screen.getByTestId("tile-below-limit")).toHaveTextContent("1 fill-in has no Action plan yet");
    expect(screen.getByTestId("value-waiting-review")).toHaveTextContent("4");
    // The review is one step (2026-11-17): there is no tile for top management any more.
    expect(screen.queryByTestId("tile-waiting-top")).not.toBeInTheDocument();
    expect(screen.getByTestId("value-plans-open")).toHaveTextContent("3");
    expect(calls.filter((call) => call.key === "POST /api/v1/management-home/summary")).toHaveLength(1);
  });

  it("sends the month, span, plant and form the address names, and nothing else", async () => {
    open("/apps/management-core/s-home?month=2026-09&span=12&plant=P2&form=11111111-1111-1111-1111-111111111111");

    await screen.findByTestId("value-plants-sent");

    const body = JSON.parse(calls.find((call) => call.key === "POST /api/v1/management-home/summary")!.body);
    expect(body).toEqual({ month: "2026-09", span: 12, plant: "P2", form: "11111111-1111-1111-1111-111111111111" });
  });

  it("opens the list a tile counts, already narrowed to its month, its plant and its step", async () => {
    open();
    await screen.findByTestId("value-plants-sent");

    expect(screen.getByTestId("tile-plants-sent")).toHaveAttribute("href", "/apps/management-core/s-fill-ins?period=2026-11&sent=1&plan=none");
    expect(screen.getByTestId("tile-below-limit")).toHaveAttribute("href", "/apps/management-core/s-fill-ins?period=2026-11&needs=1");
    expect(screen.getByTestId("tile-waiting-review")).toHaveAttribute("href", "/apps/management-core/s-fill-ins?waiting=review");
    expect(screen.getByTestId("tile-plans-open")).toHaveAttribute("href", "/apps/management-core/s-fill-ins?plan=open");
  });

  it("carries the plant a person chose into the lists the tiles open", async () => {
    open("/apps/management-core/s-home", summary({ applied: { plant: "P2" } }));
    await screen.findByTestId("value-plants-sent");

    expect(screen.getByTestId("tile-waiting-review")).toHaveAttribute("href", "/apps/management-core/s-fill-ins?waiting=review&plant=P2");
  });

  it("says the legend in the numbers the server sent, and the limit apart from the bands", async () => {
    open();

    expect(await screen.findByTestId("legend-words")).toHaveTextContent(
      "Excellent: above 85%. Good: above 65% up to 85%. Poor: 65% or less. An Action plan is needed below 85%.",
    );
  });

  it("writes the score and the band's word at every plant and says no score for a plant that did not report", async () => {
    open();
    const card = await screen.findByTestId("chart-plant-scores");

    const bars = within(card).getByTestId("score-bars");
    expect(within(bars).getByText("Plant 1").closest("div,button")).toHaveAttribute("aria-label", "Plant 1: 92.5%, Excellent. Show only this plant.");
    expect(bars).toHaveTextContent("92.5%");
    expect(bars).toHaveTextContent("Excellent");
    expect(bars).toHaveTextContent("76%");
    expect(bars).toHaveTextContent("Good");
    expect(bars).toHaveTextContent("No score");
    expect(bars).not.toHaveTextContent("0%");
  });

  it("draws a gap and not a zero for a month a plant did not report", async () => {
    open();
    const card = await screen.findByTestId("chart-plant-lines");

    const first = within(card).getByTestId("line-P1");
    // Plant 1 has no score in August: five marks for six months, and two separate runs of the line.
    expect(first.querySelectorAll("title")).toHaveLength(5);
    expect(first.querySelectorAll("path.score-line")).toHaveLength(2);
    const second = within(card).getByTestId("line-P2");
    expect(second.querySelectorAll("title")).toHaveLength(2);
    expect(card.textContent).not.toMatch(/(^|\D)0%/);
  });

  it("offers every graph as a table of the very same numbers", async () => {
    open();
    const card = await screen.findByTestId("chart-plant-scores");

    fireEvent.click(within(card).getByRole("button", { name: /view as table/i }));

    const table = within(card).getByRole("table");
    expect(within(table).getByText("92.5%")).toBeInTheDocument();
    expect(within(table).getByText("Excellent")).toBeInTheDocument();
    expect(within(table).getByText("Yes")).toBeInTheDocument();
    expect(within(table).getAllByText("—").length).toBeGreaterThan(0);
  });

  it("explains in the table a bar the limit now in force would judge otherwise", async () => {
    const base = summary();
    open(
      "/apps/management-core/s-home",
      summary({
        charts: {
          ...base.charts,
          scoresByPlant: [
            { plant: "P1", label: "Plant 1", score: 90, forms: 1, rank: 1, band: "GOOD", bandWord: "Good", needsActionPlan: false, belowCurrentLimit: true },
          ],
        },
      }),
    );
    const card = await screen.findByTestId("chart-plant-scores");

    fireEvent.click(within(card).getByRole("button", { name: /view as table/i }));

    expect(within(card).getByRole("table")).toHaveTextContent("No (below the limit now; decided under an earlier limit)");
  });

  it("ranks the plants that have a score, and leaves the one without out of the ranking", async () => {
    open();
    const rank = await screen.findByTestId("rank-list");

    expect(within(rank).getAllByRole("listitem")).toHaveLength(2);
    expect(rank).toHaveTextContent("Plant 1");
    expect(rank).not.toHaveTextContent("Plant 3");
  });

  it("says in plain words that no plant has a score instead of drawing an empty graph", async () => {
    const base = summary();
    open(
      "/apps/management-core/s-home",
      summary({
        tiles: { ...base.tiles, averageScore: { atLeast: false } },
        charts: { ...base.charts, scoresByPlant: [], plantsOverMonths: [], averageByMonth: base.months.map((month) => ({ month, plants: 0 })) },
      }),
    );

    const card = await screen.findByTestId("chart-plant-scores");
    expect(card).toHaveTextContent("No plant has an approved fill-in for Nov 2026 yet.");
    expect(screen.getByTestId("tile-average-score")).toHaveTextContent("No approved fill-in for Nov 2026 yet");
    expect(screen.queryByTestId("value-average-score")).toBeNull();
  });

  it("shows the Action plans a plant owes beside the ones it has, and draws every chart in solid colours", async () => {
    const { container } = open();
    await screen.findByTestId("plans-by-plant");

    expect(screen.getByTestId("plans-P3")).toHaveAccessibleName("Plant 3: 2 owed and not started, 0 being written or returned, 0 sent, 0 approved");
    expect(screen.getByTestId("plans-P3")).toHaveTextContent("2 · 0 · 0 · 0");
    expect(screen.getByTestId("plans-by-plant")).toHaveTextContent("Owed, not started");
    // No hatch, dot or wave patterns and no dashed lines in Management Core (owner's choice, 2026-11-17).
    expect(container.querySelector("pattern")).toBeNull();
    expect(container.querySelector("[fill^='url(']")).toBeNull();
    container.querySelectorAll<SVGPathElement>(".score-line").forEach((line) => {
      expect(line.style.strokeDasharray).toBe("");
    });
  });

  it("draws no tile for a section the server did not send, and no zero in its place", async () => {
    const base = summary();
    open("/apps/management-core/s-home", summary({ tiles: { ...base.tiles, waitingForReview: undefined, waitingForTopManagement: undefined } }));
    await screen.findByTestId("value-plants-sent");

    expect(screen.queryByTestId("tile-waiting-review")).toBeNull();
    expect(screen.queryByTestId("tile-waiting-top")).toBeNull();
    expect(screen.queryByTestId("tile-failed")).toBeNull();
  });

  it("names a section that failed and still draws the others", async () => {
    const base = summary();
    open(
      "/apps/management-core/s-home",
      summary({
        tiles: { ...base.tiles, actionPlansOpen: undefined },
        charts: { ...base.charts, actionPlansByPlant: undefined },
        problems: ["action-plans"],
      }),
    );

    expect(await screen.findByTestId("tile-failed")).toHaveTextContent("Could not be worked out");
    expect(screen.getByTestId("value-plants-sent")).toHaveTextContent("2 of 3");
    expect(screen.getByTestId("chart-plans")).toHaveTextContent("This graph could not be worked out");
  });

  it("says that waiting and Action plan numbers are not narrowed when a form is chosen", async () => {
    open("/apps/management-core/s-home?form=11111111-1111-1111-1111-111111111111");

    expect(await screen.findByTestId("tile-waiting-review")).toHaveTextContent("Not narrowed by form");
  });

  it("puts a choice in the address, so a view can be shared", async () => {
    open();
    await screen.findByTestId("value-plants-sent");

    fireEvent.change(screen.getByLabelText("Plant"), { target: { value: "P2" } });
    await waitFor(() => expect(fakeRouter.push).toHaveBeenCalledWith("/apps/management-core/s-home?plant=P2", { scroll: false }));
    fireEvent.change(screen.getByLabelText("Compare over"), { target: { value: "3" } });
    await waitFor(() => expect(fakeRouter.push).toHaveBeenCalledWith("/apps/management-core/s-home?span=3&plant=P2", { scroll: false }));
  });

  it("shows the API's words when the page cannot be had, with a way to try again", async () => {
    calls = fakeApi({
      "POST /api/v1/management-home/summary": () =>
        json({ error: { code: "NOT_FOUND", message: "This application does not exist.", requestId: "r1" } }, 404),
    });
    setUrl("/apps/management-core/s-home");
    render(<ManagementHomePage />);

    expect(await screen.findByTestId("home-failed")).toHaveTextContent("This application does not exist.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
