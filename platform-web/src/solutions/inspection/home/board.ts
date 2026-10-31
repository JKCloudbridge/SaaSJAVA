import type { IconName } from "@/components/ui/Icon";
import { formatCount, formatDays, formatPercent, type HomeSummary } from "./model";
import type { TileView } from "./StatTile";

/**
 * The pure parts of the third look of the home page, the board (FA13C): which of the page's numbers stand in the six
 * tiles and the six cards, and the sentences of the message panel. It adds up nothing and decides nothing: every number
 * here was counted by the server and is written down by the page; this only chooses where it stands.
 */

/** One tile of the page as the page works it out: a number or a failure. */
export interface BoardEntry {
  failed: boolean;
  label: string;
  view: TileView;
}

/** The six tiles of the board, in order, each with its picture. */
const TILES: readonly { id: string; icon: IconName }[] = [
  { id: "active-templates", icon: "book" },
  { id: "waiting-approval", icon: "clock" },
  { id: "my-tasks", icon: "clipboard" },
  { id: "open-requests", icon: "tag" },
  { id: "fills-sent", icon: "table" },
  { id: "average-days", icon: "chart" },
];

/** The picture of a tile of the page: the six of the board keep theirs, the others have one of their own. */
export function tileIcon(id: string): IconName | undefined {
  return TILES.find((one) => one.id === id)?.icon ?? EXTRA_ICONS[id];
}

const EXTRA_ICONS: Readonly<Record<string, IconName>> = {
  "fills-waiting": "clock",
  published: "star",
  "rejected-share": "shield",
  drafts: "folder",
  "approved-unpublished": "bolt",
};

export interface BoardTile {
  entry: BoardEntry;
  icon: IconName;
}

/** The six tiles the page has; a section that failed is kept (it says so) and the rest follow the fixed order. */
export function boardTiles(entries: readonly BoardEntry[]): BoardTile[] {
  const chosen: BoardTile[] = [];
  for (const { id, icon } of TILES) {
    const entry = entries.find((one) => !one.failed && one.view.id === id);
    if (entry) {
      chosen.push({ entry, icon });
    }
  }
  for (const entry of entries) {
    if (entry.failed) {
      chosen.push({ entry, icon: "bolt" });
    }
  }
  return chosen;
}

export interface BoardFigure {
  label: string;
  value: string;
  href?: string;
}

export interface BoardCard {
  key: string;
  title: string;
  icon: IconName;
  figures: BoardFigure[];
}

const CARDS: readonly { key: string; title: string; icon: IconName; ids: readonly string[] }[] = [
  { key: "documents", title: "Document Control", icon: "book", ids: ["drafts", "approved-unpublished"] },
  { key: "approvals", title: "Approvals", icon: "shield", ids: ["waiting-approval", "rejected-share"] },
  { key: "records", title: "Record Control", icon: "folder", ids: ["fills-sent", "fills-waiting"] },
  { key: "speed", title: "Decision speed", icon: "clock", ids: ["average-days"] },
  { key: "publishing", title: "Templates in use", icon: "star", ids: ["active-templates", "published"] },
  { key: "attention", title: "Needs attention", icon: "bell", ids: ["my-tasks", "open-requests"] },
];

/**
 * The six cards, each with the figures it has. A figure whose section failed or that the page does not show (a filter
 * hides it) is left out, and a card with no figure at all is not drawn. Decision speed names its slowest step.
 */
export function boardCards(entries: readonly BoardEntry[], summary: HomeSummary | undefined): BoardCard[] {
  const cards: BoardCard[] = [];
  for (const card of CARDS) {
    const figures: BoardFigure[] = [];
    for (const id of card.ids) {
      const view = entries.find((one) => !one.failed && one.view.id === id)?.view;
      if (view && view.value !== undefined) {
        figures.push({ label: view.label, value: view.value, href: view.href });
      }
    }
    if (card.key === "speed") {
      const slowest = slowestStep(summary);
      if (slowest) {
        figures.push({ label: `Slowest step: ${slowest.name}`, value: formatDays(slowest.averageDays) });
      }
    }
    if (figures.length > 0) {
      cards.push({ key: card.key, title: card.title, icon: card.icon, figures });
    }
  }
  return cards;
}

/** The approval step that took longest on average, or undefined when no step finished in the period. */
export function slowestStep(summary: HomeSummary | undefined): { name: string; averageDays: number } | undefined {
  const steps = summary?.charts.approvalStepDays ?? [];
  return steps.reduce<{ name: string; averageDays: number } | undefined>(
    (worst, one) => (worst === undefined || one.averageDays > worst.averageDays ? one : worst),
    undefined,
  );
}

/**
 * The lines of the message panel: what the numbers say today, in plain words, at most four. They come only from the
 * page's own numbers; when there is nothing to say the panel says that, so it never shows an invented figure.
 */
export function boardMessage(summary: HomeSummary | undefined, tasks: number | null | undefined): string[] {
  if (!summary) {
    return [];
  }
  const lines: string[] = [];
  const waiting = summary.tiles.waitingApproval?.value;
  if (waiting !== undefined && waiting > 0) {
    lines.push(`${formatCount(waiting)} ${waiting === 1 ? "template is" : "templates are"} waiting for approval.`);
  }
  const slowest = slowestStep(summary);
  if (slowest) {
    lines.push(`${slowest.name} is the slowest step, at ${formatDays(slowest.averageDays)} on average.`);
  }
  const rejected = summary.tiles.rejectedShare?.value;
  if (rejected !== undefined && rejected > 0) {
    lines.push(`${formatPercent(rejected)} of the templates decided were rejected.`);
  }
  if (typeof tasks === "number" && tasks > 0) {
    lines.push(`${formatCount(tasks)} approval ${tasks === 1 ? "task waits" : "tasks wait"} for you.`);
  }
  if (lines.length === 0) {
    lines.push("Nothing is waiting. Keep the records complete and ready for review.");
  }
  return lines.slice(0, 4);
}

/** The four reminders of the strip at the foot of the board: fixed words, the same for every organization. */
export const BOARD_TAKEAWAYS: readonly { icon: IconName; title: string; text: string }[] = [
  { icon: "chart", title: "Measure what matters", text: "Follow the trend, not only the number." },
  { icon: "clock", title: "Decide on time", text: "A template that waits helps nobody." },
  { icon: "folder", title: "Keep records complete", text: "Every fill-in sent, every Revision traceable." },
  { icon: "shield", title: "Stay audit ready", text: "Every day, not just before an audit." },
];

export const BOARD_CLOSING = "Strong systems. Strong quality.";
