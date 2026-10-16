import type { components } from "@/lib/api/generated/schema";
import { uid } from "./ids";
import { insertAt, move, removeAt } from "./reorder";

type NavigationItem = components["schemas"]["NavigationItem"];

export type EntryType = NavigationItem["type"];

/** One entry of an application's navigation as the builder holds it. `id` identifies it in the browser only. */
export interface NavEntry {
  id: string;
  type: EntryType;
  label: string;
  object: string;
  page: string;
  path: string;
  tab: string;
}

const BLANK = { label: "", object: "", page: "", path: "", tab: "" };

export function entryFromItem(item: NavigationItem): NavEntry {
  return {
    id: uid(),
    type: item.type,
    label: item.label ?? "",
    object: item.object ?? "",
    page: item.page ?? "",
    path: item.path ?? "",
    tab: item.tab ?? "",
  };
}

export function entriesFromItems(items: readonly NavigationItem[]): NavEntry[] {
  return items.map(entryFromItem);
}

/** What is saved: each entry carries only the properties its type takes. */
export function itemFromEntry(entry: NavEntry): NavigationItem {
  const item: NavigationItem = { type: entry.type };
  if (entry.label) {
    item.label = entry.label;
  }
  if (entry.type === "OBJECT" || entry.type === "RECORD_PAGE") {
    item.object = entry.object;
  }
  if (entry.type === "RECORD_PAGE") {
    item.page = entry.page;
  }
  if (entry.type === "LINK") {
    item.path = entry.path;
  }
  if (entry.type === "TAB") {
    item.tab = entry.tab;
  }
  return item;
}

export function itemsFromEntries(entries: readonly NavEntry[]): NavigationItem[] {
  return entries.map(itemFromEntry);
}

export function objectEntry(object: string): NavEntry {
  return { id: uid(), type: "OBJECT", ...BLANK, object };
}

export function tabEntry(tab: string): NavEntry {
  return { id: uid(), type: "TAB", ...BLANK, tab };
}

export function headingEntry(label = ""): NavEntry {
  return { id: uid(), type: "HEADING", ...BLANK, label };
}

export function linkEntry(): NavEntry {
  return { id: uid(), type: "LINK", ...BLANK };
}

export function recordPageEntry(): NavEntry {
  return { id: uid(), type: "RECORD_PAGE", ...BLANK };
}

/** Whether the object or the tab is in the navigation already (the palette dims what is, and does not add it twice). */
export function isListed(entries: readonly NavEntry[], type: "OBJECT" | "TAB", name: string): boolean {
  return entries.some((entry) => entry.type === type && (type === "OBJECT" ? entry.object : entry.tab) === name);
}

export function changeEntry(entries: readonly NavEntry[], index: number, change: Partial<NavEntry>): NavEntry[] {
  return entries.map((entry, at) => (at === index ? { ...entry, ...change } : entry));
}

/**
 * The entries a person still has to finish before the API will accept them. A hint while building; the API checks the
 * saved navigation again and names the item that is wrong.
 */
export function unfinished(entries: readonly NavEntry[]): number[] {
  const open: number[] = [];
  entries.forEach((entry, at) => {
    const done =
      (entry.type === "OBJECT" && entry.object !== "") ||
      (entry.type === "RECORD_PAGE" && entry.object !== "" && entry.page !== "") ||
      (entry.type === "TAB" && entry.tab !== "") ||
      (entry.type === "LINK" && entry.path !== "" && entry.label !== "") ||
      (entry.type === "HEADING" && entry.label !== "");
    if (!done) {
      open.push(at);
    }
  });
  return open;
}

/** Puts an entry before the entry at `before` (or at the end). */
export function insertEntry(entries: readonly NavEntry[], entry: NavEntry, before?: number): NavEntry[] {
  return insertAt(entries, entry, before);
}

/**
 * Moves the entry at `from` so that it sits before the entry that was at `before` (or at the end). `before` counts the
 * list as it is when the drag starts, which is what a drop on a row knows.
 */
export function moveBefore(entries: readonly NavEntry[], from: number, before?: number): NavEntry[] {
  const at = before === undefined ? entries.length : before;
  const to = from < at ? at - 1 : at;
  return move(entries, from, Math.min(Math.max(to, 0), entries.length - 1));
}

export function removeEntry(entries: readonly NavEntry[], index: number): NavEntry[] {
  return removeAt(entries, index);
}

export function nudgeEntry(entries: readonly NavEntry[], index: number, delta: number): NavEntry[] {
  return move(entries, index, index + delta);
}
