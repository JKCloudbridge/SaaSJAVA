import type { components } from "@/lib/api/generated/schema";
import { allComponents, emptyComponent, type ComponentDraft, type TabDraft } from "./recordPageDraft";
import { insertAt, move, removeAt, updateAt } from "./reorder";

type TabView = components["schemas"]["TabView"];
type RelationshipView = components["schemas"]["RelationshipView"];

/** A key that no component of the page uses yet (keys name a component inside the page and must be unique). */
export function nextKey(used: readonly ComponentDraft[], prefix: string): string {
  const taken = new Set(allComponents(used).map((component) => component.key));
  for (let number = 1; ; number += 1) {
    const key = `${prefix}-${number}`;
    if (!taken.has(key)) {
      return key;
    }
  }
}

/** The details of the page, drawn from its layout. */
export function detailsComponent(page: readonly ComponentDraft[]): ComponentDraft {
  return { ...emptyComponent("DETAILS", 0), key: nextKey(page, "details") };
}

/** The records of another object that point at this one, as a list. The columns are the first fields of that object. */
export function relatedListComponent(
  page: readonly ComponentDraft[],
  relationship: Pick<RelationshipView, "childObject" | "field" | "listLabel">,
  columns: readonly string[],
): ComponentDraft {
  return {
    ...emptyComponent("RELATED_LIST", 0),
    key: nextKey(page, "related"),
    object: relationship.childObject,
    field: relationship.field,
    label: relationship.listLabel,
    columns: [...columns],
  };
}

/** A tab of the organization placed on the page. */
export function tabComponent(page: readonly ComponentDraft[], tabApiName: string): ComponentDraft {
  return { ...emptyComponent("TAB", 0), key: nextKey(page, "tab"), tab: tabApiName };
}

export function addTab(tabs: readonly TabDraft[], label = ""): TabDraft[] {
  return [...tabs, { label: label || `Tab ${tabs.length + 1}`, components: [] }];
}

export function renameTab(tabs: readonly TabDraft[], index: number, label: string): TabDraft[] {
  return updateAt(tabs, index, (tab) => ({ ...tab, label }));
}

export function removeTab(tabs: readonly TabDraft[], index: number): TabDraft[] {
  return removeAt(tabs, index);
}

export function nudgeTab(tabs: readonly TabDraft[], index: number, delta: number): TabDraft[] {
  return move(tabs, index, index + delta);
}

export function moveTabTo(tabs: readonly TabDraft[], from: number, to: number): TabDraft[] {
  return move(tabs, from, to);
}

/** Puts a component into a tab at `at` (or at the end). */
export function addComponent(
  tabs: readonly TabDraft[],
  tabIndex: number,
  component: ComponentDraft,
  at?: number,
): TabDraft[] {
  return updateAt(tabs, tabIndex, (tab) => ({ ...tab, components: insertAt(tab.components, component, at) }));
}

export function removeComponent(tabs: readonly TabDraft[], tabIndex: number, index: number): TabDraft[] {
  return updateAt(tabs, tabIndex, (tab) => ({ ...tab, components: removeAt(tab.components, index) }));
}

export function changeComponent(
  tabs: readonly TabDraft[],
  tabIndex: number,
  index: number,
  next: ComponentDraft,
): TabDraft[] {
  return updateAt(tabs, tabIndex, (tab) => ({ ...tab, components: updateAt(tab.components, index, () => next) }));
}

/** Moves a component within its tab, or into another tab (at `at`, or at the end). */
export function moveComponent(
  tabs: readonly TabDraft[],
  from: { tab: number; index: number },
  to: { tab: number; at?: number },
): TabDraft[] {
  const taken = tabs[from.tab]?.components[from.index];
  if (!taken || !tabs[to.tab]) {
    return [...tabs];
  }
  const removed = removeComponent(tabs, from.tab, from.index);
  // Dropping later in the tab the component came from: the list is one shorter once it is taken out.
  const at = to.at !== undefined && to.tab === from.tab && from.index < to.at ? to.at - 1 : to.at;
  return addComponent(removed, to.tab, taken, at);
}

export function nudgeComponent(tabs: readonly TabDraft[], tabIndex: number, index: number, delta: number): TabDraft[] {
  return updateAt(tabs, tabIndex, (tab) => ({ ...tab, components: move(tab.components, index, index + delta) }));
}

/** The tab (a record page element made of a custom page) that shows a custom page, and whether it has to be made. */
export interface TabTarget {
  /** The API name the tab has, or will have once made. */
  apiName: string;
  /** The name to send when making it (the platform adds the ending). */
  name: string;
  label: string;
  existing: boolean;
}

const SUFFIX = "__c";

export function withoutSuffix(apiName: string): string {
  return apiName.endsWith(SUFFIX) ? apiName.slice(0, -SUFFIX.length) : apiName;
}

/**
 * Which tab shows a custom page: the tab that already names it, else a tab to make (named like the page; if that name is
 * taken by a tab of another page, with "Tab" added). A custom page is never shown directly on a record page, only through
 * a tab, so dropping a page on a record page needs this answer.
 */
export function tabForPage(page: { apiName: string; label: string }, tabs: readonly TabView[]): TabTarget {
  const found = tabs.find((tab) => tab.definition.customPage === page.apiName);
  if (found) {
    return { apiName: found.apiName, name: withoutSuffix(found.apiName), label: found.label, existing: true };
  }
  const base = withoutSuffix(page.apiName);
  let name = base;
  for (let attempt = 1; tabs.some((tab) => tab.apiName === name + SUFFIX); attempt += 1) {
    name = `${base}Tab${attempt > 1 ? attempt : ""}`;
  }
  return { apiName: name + SUFFIX, name, label: page.label, existing: false };
}

/** What a chip in a tab says about its component. */
export function describeComponent(
  component: ComponentDraft,
  labelOfTab: (tabApiName: string) => string | undefined,
): string {
  switch (component.type) {
    case "DETAILS":
      return "Details";
    case "RELATED_LIST":
      return component.label || `Related: ${component.object || "choose an object"}`;
    case "TAB":
      return component.label || labelOfTab(component.tab) || component.tab || "A tab";
    case "ACTIVITY":
      return "Activity";
    case "HISTORY":
      return "History";
    default:
      return component.type;
  }
}
