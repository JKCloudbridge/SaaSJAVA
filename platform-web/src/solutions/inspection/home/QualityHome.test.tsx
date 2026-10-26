import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeRouter, setUrl } from "@/test/navigation";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
import type { HomeSummary } from "./model";
import { QualityHomePage } from "./QualityHome";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const DAY_COUNTS = [1, 2, 3, 5, 0, 0, 4];
const DAYS = DAY_COUNTS.map((count, index) => ({ day: `2026-10-${String(20 + index)}`, count }));

function summary(changes: Partial<HomeSummary> = {}): HomeSummary {
  return {
    generatedAt: "2026-10-26T09:30:00Z",
    period: {
      key: "30d",
      from: "2026-09-27T00:00:00Z",
      to: "2026-10-27T00:00:00Z",
      previousFrom: "2026-08-28T00:00:00Z",
      previousTo: "2026-09-27T00:00:00Z",
    },
    filters: {
      locations: [
        { code: "HEAD", label: "Head office", departments: [{ code: "QA", label: "Quality" }] },
        { code: "PLANT", label: "Plant", departments: [{ code: "QA", label: "Quality" }, { code: "PRD", label: "Production" }] },
      ],
    },
    applied: {},
    tiles: {
      activeTemplates: { value: 12, atLeast: false, byLocation: true, byDepartment: true },
      waitingApproval: { value: 3, extra: 9, atLeast: false, byLocation: false, byDepartment: false },
      openChangeRequests: { value: 2, atLeast: false, byLocation: false, byDepartment: false },
      fillsSubmitted: { value: 15, previous: 12, atLeast: false, byLocation: true, byDepartment: true },
      fillsWaiting: { value: 4, atLeast: false, byLocation: true, byDepartment: true },
      templatesPublished: { value: 2, previous: 5, atLeast: false, byLocation: true, byDepartment: true },
      averageApprovalDays: { value: 3.5, previous: 2, atLeast: false, byLocation: false, byDepartment: false },
      rejectedShare: { value: 25, previous: 25, atLeast: false, byLocation: false, byDepartment: false },
    },
    charts: {
      fillsPerDay: { total: 15, days: DAYS, atLeast: false },
      templatesByStatus: [
        { key: "DRAFT", label: "Draft", count: 4 },
        { key: "IN_REVIEW", label: "In review", count: 3 },
        { key: "APPROVED", label: "Approved, not published", count: 1 },
        { key: "ACTIVE", label: "Active", count: 12 },
        { key: "RETIRED", label: "Retired", count: 2 },
        { key: "NO_SHEET", label: "No revision yet", count: 0 },
      ],
      templatesByLevelAndType: [
        { level: "L1", levelLabel: "Level 1", type: "WI", typeLabel: "Work instruction", count: 5 },
        { level: "L1", levelLabel: "Level 1", type: "SOP", typeLabel: "Procedure", count: 2 },
        { level: "L2", levelLabel: "Level 2", type: "WI", typeLabel: "Work instruction", count: 3 },
      ],
      fillsByTemplate: [
        { templateId: "11111111-1111-1111-1111-111111111111", name: "Fire exit check", count: 9 },
        { templateId: "22222222-2222-2222-2222-222222222222", name: "Press line check", count: 6 },
      ],
      fillsByDepartment: [
        { location: "PLANT", department: "QA", label: "Plant · Quality", count: 8 },
        { label: "Not recorded", count: 7 },
      ],
      approvalStepDays: [{ position: 1, name: "Plant QMS", averageDays: 2.5, samples: 8 }],
      publishedPerMonth: [
        { month: "2026-09", count: 1 },
        { month: "2026-10", count: 2 },
      ],
      recentActivity: [
        {
          kind: "PUBLISHED",
          at: "2026-10-25T10:00:00Z",
          who: "User A",
          about: "template",
          id: "11111111-1111-1111-1111-111111111111",
          name: "Fire exit check",
        },
      ],
    },
    problems: [],
    ...changes,
  };
}

let calls: Call[];

function open(address = "/apps/inspection/s-home", answer: HomeSummary = summary(), tasks: number | null = 5) {
  calls = fakeApi({
    "POST /api/v1/quality-home/summary": () => json({ data: answer }),
    "GET /api/v1/approvals/inbox/summary": () =>
      tasks === null ? json({ data: { openTasks: 0, available: false } }) : json({ data: { openTasks: tasks, available: true } }),
  });
  setUrl(address);
  return render(<QualityHomePage />);
}

beforeEach(() => {
  fakeRouter.push.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("the home page of the Quality Management System", () => {
  it("shows the numbers on top, each with its words, and asks the server once for the page", async () => {
    open();

    expect(await screen.findByTestId("value-active-templates")).toHaveTextContent("12");
    expect(screen.getByTestId("value-waiting-approval")).toHaveTextContent("3");
    expect(screen.getByTestId("tile-waiting-approval")).toHaveTextContent("Oldest has waited 9 days");
    expect(screen.getByTestId("value-fills-sent")).toHaveTextContent("15");
    expect(screen.getByTestId("tile-fills-sent")).toHaveTextContent("Up 25% on the 30 days before");
    expect(screen.getByTestId("tile-published")).toHaveTextContent("Down 60% on the 30 days before");
    expect(screen.getByTestId("value-average-days")).toHaveTextContent("3.5 days");
    expect(screen.getByTestId("tile-average-days")).toHaveTextContent("Up 1.5 days on the 30 days before");
    expect(screen.getByTestId("tile-rejected-share")).toHaveTextContent("No change on the 30 days before");
    expect(calls.filter((call) => call.key === "POST /api/v1/quality-home/summary")).toHaveLength(1);
  });

  it("sends the period and filters the address names, and nothing else", async () => {
    open("/apps/inspection/s-home?period=90d&location=PLANT&department=QA");

    await screen.findByTestId("value-active-templates");

    const body = JSON.parse(calls.find((call) => call.key === "POST /api/v1/quality-home/summary")!.body);
    expect(body).toEqual({ period: "90d", location: "PLANT", department: "QA" });
  });

  it("opens the list a tile counts, already narrowed", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    expect(screen.getByTestId("tile-active-templates")).toHaveAttribute("href", "/apps/inspection/s-templates?state=ACTIVE");
    expect(screen.getByTestId("tile-waiting-approval")).toHaveAttribute("href", "/apps/inspection/s-templates?state=IN_REVIEW");
    expect(screen.getByTestId("tile-my-tasks")).toHaveAttribute("href", "/approvals");
    expect(screen.getByTestId("tile-open-requests")).toHaveAttribute("href", "/apps/inspection/s-change-requests");
    expect(screen.getByTestId("tile-fills-sent")).toHaveAttribute("href", "/apps/inspection/s-fill-ins?status=SUBMITTED");
  });

  it("draws the two tiles that only tell as plain boxes, not as links", async () => {
    open();
    await screen.findByTestId("value-average-days");

    expect(screen.getByTestId("tile-average-days").tagName).toBe("DIV");
    expect(screen.getByTestId("tile-rejected-share").tagName).toBe("DIV");
  });

  it("shows how many approval tasks wait for the person, from the endpoint the header uses", async () => {
    open();

    await waitFor(() => expect(screen.getByTestId("value-my-tasks")).toHaveTextContent("5"));
  });

  it("draws no tile of tasks where the organization has no approvals", async () => {
    open("/apps/inspection/s-home", summary(), null);
    await screen.findByTestId("value-active-templates");

    expect(screen.queryByTestId("tile-my-tasks")).toBeNull();
  });

  it("draws no tile for a section the server did not send, and no zero in its place", async () => {
    const base = summary();
    open("/apps/inspection/s-home", summary({ tiles: { ...base.tiles, fillsWaiting: undefined, openChangeRequests: undefined } }));
    await screen.findByTestId("value-active-templates");

    expect(screen.queryByTestId("tile-fills-waiting")).toBeNull();
    expect(screen.queryByTestId("tile-open-requests")).toBeNull();
    expect(screen.queryByTestId("tile-failed")).toBeNull();
  });

  it("says a section could not be worked out, and still draws the others", async () => {
    const base = summary();
    open(
      "/apps/inspection/s-home",
      summary({
        problems: ["fill-ins"],
        tiles: { ...base.tiles, fillsSubmitted: undefined, fillsWaiting: undefined },
        charts: { ...base.charts, fillsPerDay: undefined, fillsByTemplate: undefined, fillsByDepartment: undefined },
      }),
    );

    expect(await screen.findByTestId("tile-failed")).toHaveTextContent("Could not be worked out");
    expect(screen.getByTestId("value-active-templates")).toHaveTextContent("12");
    expect(within(screen.getByTestId("chart-fills-per-day")).getByRole("status")).toHaveTextContent("Could not be worked out");
  });

  it("says plainly that a tile does not follow a filter that was chosen", async () => {
    open("/apps/inspection/s-home?department=QA", summary({ applied: { department: "QA" } }));

    expect(await screen.findByTestId("tile-waiting-approval")).toHaveTextContent("Not filtered by department");
    expect(screen.getByTestId("tile-active-templates")).not.toHaveTextContent("Not filtered");
  });

  it("says why a tile has no number instead of showing a zero", async () => {
    const base = summary();
    open("/apps/inspection/s-home", summary({ tiles: { ...base.tiles, averageApprovalDays: { atLeast: false, byLocation: false, byDepartment: false } } }));

    expect(await screen.findByTestId("tile-average-days")).toHaveTextContent("No templates were decided in this period");
    expect(screen.queryByTestId("value-average-days")).toBeNull();
  });

  it("says at least when the server stopped reading before the end", async () => {
    const base = summary();
    open("/apps/inspection/s-home", summary({ tiles: { ...base.tiles, activeTemplates: { value: 50000, atLeast: true, byLocation: true, byDepartment: true } } }));

    expect(await screen.findByTestId("value-active-templates")).toHaveTextContent("at least 50,000");
  });

  it("shows placeholders shaped like the tiles while the first answer is on its way", () => {
    fakeApi({ "POST /api/v1/quality-home/summary": () => new Promise<Response>(() => undefined) });
    setUrl("/apps/inspection/s-home");
    render(<QualityHomePage />);

    expect(screen.getByTestId("home-tiles-loading").children).toHaveLength(9);
    expect(screen.getByTestId("quality-home")).toHaveAttribute("aria-busy", "true");
  });

  it("shows the server's own words when the page cannot be had, and tries again on request", async () => {
    let healthy = false;
    fakeApi({
      "POST /api/v1/quality-home/summary": () =>
        healthy ? json({ data: summary() }) : refusal("SERVICE_UNAVAILABLE", "Try again later.", 503),
      "GET /api/v1/approvals/inbox/summary": () => json({ data: { openTasks: 0, available: false } }),
    });
    setUrl("/apps/inspection/s-home");
    render(<QualityHomePage />);

    expect(await screen.findByTestId("home-failed")).toBeInTheDocument();
    healthy = true;
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByTestId("value-active-templates")).toBeInTheDocument();
  });
});

describe("the filters of the home page", () => {
  it("puts a choice in the address, so a view can be shared and the back button undoes it", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    fireEvent.click(screen.getByRole("radio", { name: "90 days" }));

    expect(fakeRouter.push).toHaveBeenCalledWith("/apps/inspection/s-home?period=90d", { scroll: false });
  });

  it("offers the locations and departments the server named, and the departments of the chosen location", async () => {
    open("/apps/inspection/s-home?location=HEAD");
    await screen.findByTestId("value-active-templates");

    const department = screen.getByRole("combobox", { name: /Department/ });
    const options = within(department).getAllByRole("option").map((option) => option.textContent);

    expect(options).toEqual(["All departments", "Quality"]);
    expect(within(screen.getByRole("combobox", { name: /Location/ })).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "All locations",
      "Head office",
      "Plant",
    ]);
  });

  it("forgets a department the new location does not have", async () => {
    open("/apps/inspection/s-home?location=PLANT&department=PRD");
    await screen.findByTestId("value-active-templates");

    fireEvent.change(screen.getByRole("combobox", { name: /Location/ }), { target: { value: "HEAD" } });

    expect(fakeRouter.push).toHaveBeenCalledWith("/apps/inspection/s-home?location=HEAD", { scroll: false });
  });

  it("clears every choice with one button that appears only when there is a choice", async () => {
    open("/apps/inspection/s-home?period=7d&location=PLANT");
    await screen.findByTestId("value-active-templates");

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));

    expect(fakeRouter.push).toHaveBeenCalledWith("/apps/inspection/s-home", { scroll: false });
  });

  it("has no Clear button on the page as it opens", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    expect(screen.queryByRole("button", { name: "Clear" })).toBeNull();
  });

  it("refreshes on request and says when the numbers were worked out", async () => {
    open();
    await screen.findByTestId("value-active-templates");
    const before = calls.filter((call) => call.key === "POST /api/v1/quality-home/summary").length;

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(calls.filter((call) => call.key === "POST /api/v1/quality-home/summary")).toHaveLength(before + 1));
    expect(screen.getByTestId("home-filters")).toHaveTextContent(/Updated at \d\d:\d\d/);
  });
});

describe("the graphs of the home page", () => {
  it("narrows the page to a department when its bar is pressed", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    fireEvent.click(within(screen.getByTestId("chart-fills-by-department")).getByRole("button", { name: /Plant · Quality/ }));

    expect(fakeRouter.push).toHaveBeenCalledWith("/apps/inspection/s-home?location=PLANT&department=QA", { scroll: false });
  });

  it("does not narrow the page to the bar that says not recorded", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    fireEvent.click(within(screen.getByTestId("chart-fills-by-department")).getByRole("button", { name: /Not recorded/ }));

    expect(fakeRouter.push).not.toHaveBeenCalled();
  });

  it("opens the list of the fill-ins of a template when its bar is pressed", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    fireEvent.click(within(screen.getByTestId("chart-fills-by-template")).getByRole("button", { name: /Fire exit check/ }));

    expect(fakeRouter.push).toHaveBeenCalledWith("/apps/inspection/s-fill-ins?template=11111111-1111-1111-1111-111111111111");
  });

  it("opens the list of templates in a state when a ring segment is pressed", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    fireEvent.click(within(screen.getByTestId("chart-templates-by-status")).getByRole("button", { name: /In review/ }));

    expect(fakeRouter.push).toHaveBeenCalledWith("/apps/inspection/s-templates?state=IN_REVIEW");
  });

  it("gives every graph a table with the very numbers of the graph", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    const card = screen.getByTestId("chart-fills-per-day");
    fireEvent.click(within(card).getByRole("button", { name: "View as table" }));

    const table = within(card).getByRole("table");
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(DAYS.length);
    expect(within(rows[6]!).getAllByRole("cell")[0]).toHaveTextContent("4");
    expect(within(rows[6]!).getByRole("rowheader")).toHaveTextContent("26 Oct");
    const total = rows.reduce((sum, row) => sum + Number(within(row).getAllByRole("cell")[0]!.textContent), 0);
    expect(total).toBe(15);
  });

  it("says what a day holds to the keyboard and the pointer, in the same words", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    const area = within(screen.getByTestId("chart-fills-per-day")).getByRole("group");
    area.focus();
    fireEvent.keyDown(area, { key: "End" });

    expect(within(area).getByRole("status")).toHaveTextContent("26 Oct: 4 fill-ins");
    fireEvent.keyDown(area, { key: "ArrowLeft" });
    expect(within(area).getByRole("status")).toHaveTextContent("25 Oct: 0 fill-ins");
  });

  it("says there is nothing to show instead of drawing an empty graph", async () => {
    const base = summary();
    open(
      "/apps/inspection/s-home",
      summary({ charts: { ...base.charts, fillsPerDay: { total: 0, days: DAYS.map((day) => ({ ...day, count: 0 })), atLeast: false }, fillsByTemplate: [] } }),
    );
    await screen.findByTestId("value-active-templates");

    expect(within(screen.getByTestId("chart-fills-per-day")).getByRole("status")).toHaveTextContent("No fill-ins in this period");
    expect(within(screen.getByTestId("chart-fills-by-template")).getByRole("status")).toHaveTextContent("No fill-ins in this period");
    expect(within(screen.getByTestId("chart-fills-per-day")).queryByRole("button", { name: "View as table" })).toBeNull();
  });

  it("lists the recent activity with a link to what each item is about", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    const link = within(screen.getByTestId("chart-recent-activity")).getByRole("link", { name: "Fire exit check" });

    expect(link).toHaveAttribute("href", "/apps/inspection/s-templates?open=11111111-1111-1111-1111-111111111111");
    expect(screen.getByTestId("chart-recent-activity")).toHaveTextContent("was published by User A");
  });

  it("writes the days at each step in days, and the months of the trend by name", async () => {
    open();
    await screen.findByTestId("value-active-templates");

    expect(screen.getByTestId("chart-approval-steps")).toHaveTextContent("1. Plant QMS");
    expect(screen.getByTestId("chart-approval-steps")).toHaveTextContent("2.5 days");
    expect(screen.getByTestId("chart-published-per-month")).toHaveTextContent("Oct 26");
  });
});
