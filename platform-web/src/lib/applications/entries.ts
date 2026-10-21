import type { components } from "@/lib/api/generated/schema";

export type MyApplication = components["schemas"]["MyApplicationView"];
export type NavigationEntry = components["schemas"]["NavigationEntryView"];

/**
 * The shape of the routes the application runtime owns (Sprint 18, ADR-0128). The browser invents none of this: an
 * application and its entries come from the API, and these functions only turn an entry into an address and an address
 * back into an entry. Which entries exist, and which the member may open, is the API's answer.
 */

/** The path of an application's first page; the page itself decides where to go from there. */
export function applicationHref(application: Pick<MyApplication, "apiName">): string {
  return `/apps/${encodeURIComponent(application.apiName)}`;
}

/**
 * The part of an address that names an entry inside its application, or null for an entry that has no page of its own
 * (a heading, and a link, which leaves for the address it holds). The parts are the API names the entry already has;
 * the prefix says what kind of entry it is, so a name of one kind can never be taken for another.
 */
export function entrySlug(entry: NavigationEntry): string | null {
  switch (entry.type) {
    case "OBJECT":
      return entry.objectApiName ? `o-${entry.objectApiName}` : null;
    case "RECORD_PAGE":
      return entry.objectApiName && entry.pageApiName ? `p-${entry.objectApiName}.${entry.pageApiName}` : null;
    case "CUSTOM":
      return entry.tabApiName ? `c-${entry.tabApiName}` : null;
    case "PLATFORM":
      // A page the platform ships with the application (Functional track FA1): named by its page key.
      return entry.pageKey ? `s-${entry.pageKey}` : null;
    default:
      return null;
  }
}

/** Where an entry leads: its page inside the application, the address a link holds, or nowhere (a heading). */
export function entryHref(application: Pick<MyApplication, "apiName">, entry: NavigationEntry): string | null {
  if (entry.type === "LINK") {
    return entry.path ?? null;
  }
  const slug = entrySlug(entry);
  return slug === null ? null : `${applicationHref(application)}/${encodeURIComponent(slug)}`;
}

/** The entry an address names, or undefined when the application has none like it (for example after a change). */
export function findEntry(application: MyApplication, slug: string): NavigationEntry | undefined {
  return application.items.find((entry) => entrySlug(entry) === slug);
}

/** Whether the entry can be opened now. A heading cannot, and neither can an entry the API reports as unavailable. */
export function isOpenable(entry: NavigationEntry): boolean {
  return entry.type !== "HEADING" && entry.available !== false;
}

/**
 * The page an application opens on: the first entry that is a page of its own, else the first link, else nothing.
 * A link is a fallback because it leaves the application, and an application should open inside itself when it can.
 */
export function firstPage(application: MyApplication): NavigationEntry | undefined {
  const openable = application.items.filter(isOpenable);
  return openable.find((entry) => entrySlug(entry) !== null) ?? openable.find((entry) => entry.type === "LINK");
}

/** The application with the API name, if the member has it. */
export function findApplication(applications: readonly MyApplication[], apiName: string): MyApplication | undefined {
  return applications.find((application) => application.apiName === apiName);
}

/** The words for the kind of page, for a screen that has no page to show yet. */
export function describeKind(entry: NavigationEntry): string {
  switch (entry.type) {
    case "OBJECT":
      return "list of records";
    case "RECORD_PAGE":
      return "record page";
    case "CUSTOM":
      return "custom page";
    case "PLATFORM":
      return "page";
    case "LINK":
      return "link";
    default:
      return "heading";
  }
}
