import { isComingSoon, tabCount, type MyApplication } from "@/lib/applications/entries";

/**
 * The logic of the grid of applications (FA9, ADR-0203), kept apart from the drawing so it is tested without a browser.
 * Everything here works on the list the API gave: the browser hardcodes no application, no group and no order.
 */

/** How many series colours the design has (`--series-1` to `--series-6`, tokens.css). */
export const SERIES_COUNT = 6;

/** The groups the applications name, in the order they first appear in the API's list, each once. */
export function groupsOf(applications: readonly MyApplication[]): string[] {
  const seen: string[] = [];
  for (const application of applications) {
    const group = application.group?.trim();
    if (group && !seen.includes(group)) {
      seen.push(group);
    }
  }
  return seen;
}

/**
 * The colour slot (1 to 6) of each group: by order of first appearance, so the colours stay apart for as long as there
 * are six groups or fewer, and a person who sees only some applications still sees consistent colours inside the grid.
 */
export function groupSlots(applications: readonly MyApplication[]): Map<string, number> {
  const slots = new Map<string, number>();
  groupsOf(applications).forEach((group, index) => slots.set(group, (index % SERIES_COUNT) + 1));
  return slots;
}

/** What a search matches: the name of the application, the group and its tabs, ignoring case and accents. */
function normalize(text: string): string {
  return text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
}

/**
 * The applications that match the search text and the chosen group, in the API's order. An empty search matches all.
 * Every word typed must be found in the name, the group or the name of one of the tabs.
 */
export function filterApplications(
  applications: readonly MyApplication[],
  search: string,
  group: string | null,
): MyApplication[] {
  const words = normalize(search).split(/\s+/).filter(Boolean);
  return applications.filter((application) => {
    if (group !== null && (application.group?.trim() ?? "") !== group) {
      return false;
    }
    if (words.length === 0) {
      return true;
    }
    const haystack = normalize(
      [application.label, application.group ?? "", ...application.items.map((entry) => entry.label)].join(" "),
    );
    return words.every((word) => haystack.includes(word));
  });
}

/** "7 tabs", "1 tab": the short line under the name of an application. */
export function tabsLine(application: MyApplication): string {
  const count = tabCount(application);
  return count === 1 ? "1 tab" : `${count} tabs`;
}

/** Whether the tile carries the "Coming soon" tag: every function of the application is still only announced. */
export function showsComingSoon(application: MyApplication): boolean {
  return isComingSoon(application);
}

/** "Showing 3 of 16 applications": the sentence a screen reader hears after a search. */
export function resultsLine(shown: number, total: number): string {
  if (shown === total) {
    return total === 1 ? "1 application" : `${total} applications`;
  }
  return `Showing ${shown} of ${total} applications`;
}

const RECENT_KEY = "platform.recent-applications";
const RECENT_MOST = 3;

/**
 * The applications this person opened last, newest first, as far as the browser remembers them. A convenience of one
 * browser only: nothing is sent anywhere, and when the storage cannot be used there are simply none.
 */
export function readRecent(storage: Pick<Storage, "getItem"> | null = safeStorage()): string[] {
  try {
    const raw = storage?.getItem(RECENT_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

/** Remembers that an application was opened, keeping the last few. Never fails. */
export function rememberRecent(apiName: string, storage: Pick<Storage, "getItem" | "setItem"> | null = safeStorage()) {
  try {
    const next = [apiName, ...readRecent(storage).filter((name) => name !== apiName)].slice(0, RECENT_MOST);
    storage?.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    // The storage is full or blocked: the row of recent applications is a nicety, so nothing else is done.
  }
}

/** The recent applications that this person still has, in the order they were opened. */
export function recentApplications(applications: readonly MyApplication[], recent: readonly string[]): MyApplication[] {
  return recent
    .map((name) => applications.find((application) => application.apiName === name))
    .filter((application): application is MyApplication => application !== undefined);
}

function safeStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}
