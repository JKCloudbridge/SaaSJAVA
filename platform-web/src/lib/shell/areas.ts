import { applicationHref } from "@/lib/applications/entries";

/**
 * Where the person is in the shell, which entries the side rail offers and where each person lands (UA1, ADR-0167).
 *
 * <p>Everything here is a convenience drawn from the abilities the API lists for the signed-in member: an entry that is
 * not offered cannot be opened by a link the shell draws, and an entry that is offered can still be refused by the
 * page, which then shows the API's own words. Nothing in this file decides what a person may do.
 */

/** One link of a side rail. */
export interface RailLink {
  kind: "link";
  href: string;
  label: string;
  /** The ability the API must list for the link to be drawn; none means it is drawn whenever its rail is. */
  ability?: string;
  /** A marker for something a later release builds: drawn with a "Soon" badge, still a page that says so. */
  soon?: boolean;
  /** When the page is the current one, if "the address is this or below it" is not the right test. */
  matches?: (pathname: string) => boolean;
}

/** A main item that opens and closes a list of links. A group holds links, never another group (one level). */
export interface RailGroup {
  kind: "group";
  key: string;
  label: string;
  items: RailLink[];
}

export type RailEntry = RailLink | RailGroup;

/** Whether the address is the link's page or a page below it. */
export function isCurrent(link: RailLink, pathname: string): boolean {
  if (link.matches) {
    return link.matches(pathname);
  }
  return pathname === link.href || pathname.startsWith(`${link.href}/`);
}

function link(href: string, label: string, ability?: string): RailLink {
  return { kind: "link", href, label, ability };
}

/** The address of the page that stands for "General" until the organization's branding and general settings exist. */
export const GENERAL_HREF = "/setup/general";

/** The address of the Object settings tab. */
export const OBJECT_SETTINGS_HREF = "/setup/objects";

/**
 * The rail of the Home tab, in order. The ability of a link is the one the page's first call to the API needs (the same
 * ability the API asks); a group is drawn only while at least one of its links is.
 */
const HOME_ENTRIES: readonly RailEntry[] = [
  { kind: "link", href: GENERAL_HREF, label: "General", soon: true },
  link("/members", "Members", "members.view"),
  {
    kind: "group",
    key: "access",
    label: "Access",
    items: [
      link("/setup/profiles", "Profiles", "access.manage"),
      link("/setup/access-policies", "Access policies", "access.manage"),
      link("/setup/roles", "Roles", "access.manage"),
      link("/setup/groups", "Groups", "access.manage"),
    ],
  },
  {
    kind: "group",
    key: "applications",
    label: "Applications",
    items: [link("/setup/applications", "Applications", "metadata.view"), link("/setup/tabs", "Tabs", "metadata.view")],
  },
  {
    kind: "group",
    key: "automation",
    label: "Automation",
    items: [
      link("/setup/workflows", "Workflows", "workflows.view"),
      link("/workflows/executions", "Workflow runs", "workflows.view"),
      link("/setup/approvals", "Approval processes", "approvals.view"),
      link("/setup/notification-text", "Notification text", "metadata.view"),
      link("/setup/automation-health", "Automation health", "automation.view"),
    ],
  },
  {
    kind: "group",
    key: "releases",
    label: "Releases",
    items: [link("/setup/changes", "Change sets", "metadata.view"), link("/setup/history", "History", "metadata.view")],
  },
  link("/audit", "Audit trail", "audit.view"),
];

/** The rail of the platform console. The platform role is the gate (the console frame checks it): no ability. */
const CONSOLE_ENTRIES: readonly RailEntry[] = [
  {
    kind: "link",
    href: "/console",
    label: "Organizations",
    // The list of organizations is also the page of one organization; the other sections are their own addresses.
    matches: (pathname) =>
      pathname === "/console" ||
      (pathname.startsWith("/console/organizations/") && pathname !== "/console/organizations/new"),
  },
  {
    kind: "link",
    href: "/console/organizations/new",
    label: "Set up an organization",
    matches: (p) => p === "/console/organizations/new",
  },
  { kind: "link", href: "/console/plans", label: "Plans", matches: (p) => p === "/console/plans" },
  { kind: "link", href: "/console/people", label: "Platform people", matches: (p) => p === "/console/people" },
  { kind: "link", href: "/console/sessions", label: "Sessions", matches: (p) => p === "/console/sessions" },
  {
    kind: "link",
    href: "/console/automation-health",
    label: "Automation health",
    matches: (p) => p === "/console/automation-health",
  },
  { kind: "link", href: "/console/audit", label: "Audit trail", matches: (p) => p === "/console/audit" },
];

/** The links of a list of entries, groups opened out, in order. */
export function linksOf(entries: readonly RailEntry[]): RailLink[] {
  return entries.flatMap((entry) => (entry.kind === "group" ? entry.items : [entry]));
}

/** The entries of the Home rail the abilities allow: links they list, groups with at least one such link. */
export function homeRail(abilities: readonly string[]): RailEntry[] {
  const allowed = (entry: RailLink) => entry.ability === undefined || abilities.includes(entry.ability);
  const entries = HOME_ENTRIES.flatMap((entry): RailEntry[] => {
    if (entry.kind === "link") {
      return allowed(entry) ? [entry] : [];
    }
    const items = entry.items.filter(allowed);
    return items.length > 0 ? [{ ...entry, items }] : [];
  });
  // "General" alone is no reason to draw a Home: somebody who may open nothing real has no Home.
  return linksOf(entries).some((entry) => entry.ability !== undefined) ? entries : [];
}

/** The rail of the platform console. */
export function consoleRail(): RailEntry[] {
  return [...CONSOLE_ENTRIES];
}

/** The first page of a rail that is a page of its own (not the "Soon" marker), or null. */
function firstPage(entries: readonly RailEntry[]): string | null {
  return linksOf(entries).find((entry) => !entry.soon)?.href ?? null;
}

/**
 * Where the two settings tabs lead for these abilities, or null for a tab that is not drawn: Home opens the first
 * page of the rail the person may open, Object settings opens the list of objects.
 */
export function settingsTabs(abilities: readonly string[]): { home: string | null; objects: string | null } {
  return {
    home: firstPage(homeRail(abilities)),
    objects: abilities.includes("metadata.view") ? OBJECT_SETTINGS_HREF : null,
  };
}

/** Whether the person has at least one ability the setup pages are for: the gear and both tabs depend on it. */
export function canSetUp(abilities: readonly string[]): boolean {
  const tabs = settingsTabs(abilities);
  return tabs.home !== null || tabs.objects !== null;
}

/** Where the gear leads: settings, on the first page the person may open. Null for somebody who may set nothing up. */
export function settingsLanding(abilities: readonly string[]): string | null {
  const tabs = settingsTabs(abilities);
  return tabs.home ?? tabs.objects;
}

/**
 * Where a signed-in member of an organization lands: the first application the API lists; with none, a person who may
 * set things up lands on General, and anybody else on no address (the page says no application is available yet).
 */
export function landingFor(abilities: readonly string[], applications: readonly { apiName: string }[]): string | null {
  const first = applications[0];
  if (first) {
    return applicationHref(first);
  }
  return canSetUp(abilities) ? GENERAL_HREF : null;
}

/** The part of an address that names an application and one of its pages, `/apps/<application>/<entry>`. */
export function applicationAddress(pathname: string): { application: string; entry?: string } | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] !== "apps" || !parts[1]) {
    return null;
  }
  try {
    return { application: decodeURIComponent(parts[1]), entry: parts[2] ? decodeURIComponent(parts[2]) : undefined };
  } catch {
    return null;
  }
}

/** The name the strip shows on a page that belongs to no application and to neither setup tab. */
const NEUTRAL_NAMES: readonly (readonly [string, string])[] = [
  ["/approvals", "Approvals"],
  ["/notifications", "Notifications"],
  ["/records", "Records"],
  ["/switch", "Switch organization"],
];

export type Area =
  | { kind: "application"; application: string; entry?: string }
  | { kind: "home" }
  | { kind: "objects" }
  | { kind: "console" }
  | { kind: "neutral"; name: string | null };

/** Which area of the shell an address belongs to. */
export function areaOf(pathname: string): Area {
  const application = applicationAddress(pathname);
  if (application) {
    return { kind: "application", ...application };
  }
  if (pathname === OBJECT_SETTINGS_HREF || pathname.startsWith(`${OBJECT_SETTINGS_HREF}/`)) {
    return { kind: "objects" };
  }
  if (pathname === "/console" || pathname.startsWith("/console/")) {
    return { kind: "console" };
  }
  if (linksOf(HOME_ENTRIES).some((entry) => isCurrent(entry, pathname))) {
    return { kind: "home" };
  }
  const neutral = NEUTRAL_NAMES.find(([prefix]) => pathname === prefix || pathname.startsWith(`${prefix}/`));
  return { kind: "neutral", name: neutral?.[1] ?? null };
}
