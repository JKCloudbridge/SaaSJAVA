import { describe, expect, it } from "vitest";
import { boardCards, boardMessage, boardTiles, type BoardEntry } from "./board";
import type { HomeSummary } from "./model";

function entry(id: string, value: string | undefined, extra: Partial<BoardEntry["view"]> = {}): BoardEntry {
  return { failed: false, label: id, view: { id, label: id, value, empty: "", ...extra } };
}

const ALL = [
  entry("active-templates", "12"),
  entry("waiting-approval", "3"),
  entry("my-tasks", "5"),
  entry("open-requests", "2"),
  entry("fills-waiting", "4"),
  entry("fills-sent", "15"),
  entry("published", "2"),
  entry("average-days", "3.5 days"),
  entry("rejected-share", "25%"),
  entry("drafts", "4"),
  entry("approved-unpublished", "1"),
];

function summaryWith(parts: Partial<HomeSummary["tiles"]>, steps: HomeSummary["charts"]["approvalStepDays"]): HomeSummary {
  return { tiles: parts, charts: { approvalStepDays: steps }, problems: [] } as unknown as HomeSummary;
}

describe("the board look of the home page", () => {
  it("keeps six tiles in their fixed order, whatever order the page worked them out in", () => {
    const shuffled = [...ALL].reverse();

    expect(boardTiles(shuffled).map((one) => one.entry.view.id)).toEqual([
      "active-templates",
      "waiting-approval",
      "my-tasks",
      "open-requests",
      "fills-sent",
      "average-days",
    ]);
  });

  it("keeps a section that failed, so the page says so instead of hiding it", () => {
    const failed: BoardEntry = { failed: true, label: "Fill-ins sent", view: { id: "Fill-ins sent", label: "Fill-ins sent", empty: "" } };

    const tiles = boardTiles([...ALL.filter((one) => one.view.id !== "fills-sent"), failed]);

    expect(tiles.at(-1)?.entry.failed).toBe(true);
    expect(tiles).toHaveLength(6);
  });

  it("makes six cards with two figures each, and names the slowest step on the speed card", () => {
    const cards = boardCards(
      ALL,
      summaryWith({}, [
        { position: 1, name: "Plant review", averageDays: 2, samples: 4 },
        { position: 2, name: "Corporate review", averageDays: 5.5, samples: 4 },
      ]),
    );

    expect(cards.map((card) => card.key)).toEqual(["documents", "approvals", "records", "speed", "publishing", "attention"]);
    expect(cards[0]?.figures.map((figure) => figure.value)).toEqual(["4", "1"]);
    expect(cards[3]?.figures.map((figure) => figure.label)).toEqual(["average-days", "Slowest step: Corporate review"]);
    expect(cards[3]?.figures[1]?.value).toBe("5.5 days");
  });

  it("leaves out a figure the page does not show and a card with nothing left", () => {
    const withoutDrafts = ALL.filter((one) => !["drafts", "approved-unpublished"].includes(one.view.id));

    const cards = boardCards(withoutDrafts, undefined);

    expect(cards.map((card) => card.key)).not.toContain("documents");
    expect(cards.find((card) => card.key === "speed")?.figures).toHaveLength(1);
  });

  it("writes the message from the numbers only, and says plainly when nothing waits", () => {
    const busy = summaryWith(
      { waitingApproval: { value: 1 }, rejectedShare: { value: 25 } } as HomeSummary["tiles"],
      [{ position: 1, name: "Plant review", averageDays: 2, samples: 4 }],
    );

    expect(boardMessage(busy, 2)).toEqual([
      "1 template is waiting for approval.",
      "Plant review is the slowest step, at 2 days on average.",
      "25% of the templates decided were rejected.",
      "2 approval tasks wait for you.",
    ]);
    expect(boardMessage(summaryWith({}, []), 0)).toEqual(["Nothing is waiting. Keep the records complete and ready for review."]);
    expect(boardMessage(undefined, 3)).toEqual([]);
  });
});
