import { describe, expect, it } from "vitest";
import {
  areaOf,
  canSetUp,
  consoleRail,
  GENERAL_HREF,
  homeRail,
  isCurrent,
  landingFor,
  linksOf,
  settingsLanding,
  settingsTabs,
  type RailEntry,
} from "./areas";

const EVERYTHING = [
  "members.view",
  "access.manage",
  "metadata.view",
  "workflows.view",
  "approvals.view",
  "automation.view",
  "audit.view",
];

function labels(entries: readonly RailEntry[]): string[] {
  return entries.map((entry) => entry.label);
}

describe("the Home rail", () => {
  it("lists every entry, in the agreed order and groups, for a person who holds every ability", () => {
    const rail = homeRail(EVERYTHING);

    expect(labels(rail)).toEqual([
      "General",
      "Members",
      "Access",
      "Applications",
      "Automation",
      "Releases",
      "Audit trail",
    ]);
    const group = (key: string) => rail.find((entry) => entry.kind === "group" && entry.key === key);
    expect(labels(linksOf([group("access")!]))).toEqual(["Profiles", "Access policies", "Roles", "Groups"]);
    expect(labels(linksOf([group("applications")!]))).toEqual(["Applications", "Tabs"]);
    expect(labels(linksOf([group("automation")!]))).toEqual([
      "Workflows",
      "Workflow runs",
      "Approval processes",
      "Notification text",
      "Automation health",
    ]);
    expect(labels(linksOf([group("releases")!]))).toEqual(["Change sets", "History"]);
  });

  it("keeps every address of the pages that already existed", () => {
    const hrefs = linksOf(homeRail(EVERYTHING)).map((link) => link.href);

    expect(hrefs).toEqual([
      "/setup/general",
      "/members",
      "/setup/profiles",
      "/setup/access-policies",
      "/setup/roles",
      "/setup/groups",
      "/setup/applications",
      "/setup/tabs",
      "/setup/workflows",
      "/workflows/executions",
      "/setup/approvals",
      "/setup/notification-text",
      "/setup/automation-health",
      "/setup/changes",
      "/setup/history",
      "/audit",
    ]);
  });

  it("is one level deep: a group holds links and never another group", () => {
    for (const entry of homeRail(EVERYTHING)) {
      if (entry.kind === "group") {
        expect(entry.items.every((item) => item.kind === "link")).toBe(true);
      }
    }
    for (const entry of consoleRail()) {
      expect(entry.kind).toBe("link");
    }
  });

  it("draws an entry only when the API lists the ability the page needs", () => {
    const rail = homeRail(["workflows.view", "audit.view"]);

    expect(linksOf(rail).map((link) => link.label)).toEqual(["General", "Workflows", "Workflow runs", "Audit trail"]);
  });

  it("does not draw a group that has nothing the person may open", () => {
    const rail = homeRail(["members.view", "metadata.view"]);

    // Access needs the ability to manage access, which this person lacks; Automation has one page left for them.
    expect(labels(rail)).toEqual(["General", "Members", "Applications", "Automation", "Releases"]);
    expect(rail.some((entry) => entry.kind === "group" && entry.key === "access")).toBe(false);
    expect(homeRail(["members.view"]).some((entry) => entry.kind === "group")).toBe(false);
  });

  it("shows only the part of a group the person may open", () => {
    const rail = homeRail(["metadata.view"]);
    const automation = rail.find((entry) => entry.kind === "group" && entry.key === "automation");

    expect(labels(linksOf([automation!]))).toEqual(["Notification text"]);
  });

  it("is not drawn at all for a person who may open nothing: General alone is no reason for a Home", () => {
    expect(homeRail([])).toEqual([]);
    expect(homeRail(["views.share", "workflows.replay", "sharing.manage"])).toEqual([]);
  });
});

describe("the console rail", () => {
  it("lists the seven sections of the console with the same addresses as before", () => {
    expect(consoleRail().map((entry) => [entry.label, entry.kind === "link" ? entry.href : ""])).toEqual([
      ["Organizations", "/console"],
      ["Set up an organization", "/console/organizations/new"],
      ["Plans", "/console/plans"],
      ["Platform people", "/console/people"],
      ["Sessions", "/console/sessions"],
      ["Automation health", "/console/automation-health"],
      ["Audit trail", "/console/audit"],
    ]);
  });

  it("marks Organizations for the page of one organization, but not for the page that sets one up", () => {
    type Link = Extract<RailEntry, { kind: "link" }>;
    const [organizations, setUp] = consoleRail() as [Link, Link];

    expect(isCurrent(organizations, "/console")).toBe(true);
    expect(isCurrent(organizations, "/console/organizations/9f1")).toBe(true);
    expect(isCurrent(organizations, "/console/organizations/new")).toBe(false);
    expect(isCurrent(setUp, "/console/organizations/new")).toBe(true);
  });
});

describe("the two settings tabs and the gear", () => {
  it("opens Home on the first page the person may open, and Object settings on the list of objects", () => {
    expect(settingsTabs(EVERYTHING)).toEqual({ home: "/members", objects: "/setup/objects" });
    expect(settingsTabs(["access.manage"])).toEqual({ home: "/setup/profiles", objects: null });
    expect(settingsTabs(["metadata.view"])).toEqual({ home: "/setup/applications", objects: "/setup/objects" });
    expect(settingsTabs(["audit.view"])).toEqual({ home: "/audit", objects: null });
  });

  it("draws no tab for a person without an ability the pages are for", () => {
    expect(settingsTabs([])).toEqual({ home: null, objects: null });
    expect(settingsTabs(["views.share"])).toEqual({ home: null, objects: null });
  });

  it("offers the gear to a person with at least one setup ability and to nobody else", () => {
    expect(canSetUp(["workflows.view"])).toBe(true);
    expect(canSetUp(["audit.view"])).toBe(true);
    expect(canSetUp([])).toBe(false);
    expect(canSetUp(["workflows.replay", "sharing.manage"])).toBe(false);
  });

  it("sends the gear to the first page of settings, never to the General marker", () => {
    expect(settingsLanding(["metadata.view"])).toBe("/setup/applications");
    expect(settingsLanding([])).toBeNull();
  });
});

describe("where a member lands", () => {
  const apps = [{ apiName: "Field__c" }, { apiName: "Sales__c" }];

  it("lands on the first application the API lists, whoever the person is", () => {
    expect(landingFor([], apps)).toBe("/apps/Field__c");
    expect(landingFor(EVERYTHING, apps)).toBe("/apps/Field__c");
  });

  it("lands on General when there is no application and the person may set things up", () => {
    expect(landingFor(["metadata.view"], [])).toBe(GENERAL_HREF);
  });

  it("lands nowhere when there is no application and nothing to set up: the page says so", () => {
    expect(landingFor([], [])).toBeNull();
  });
});

describe("the areas of the shell", () => {
  it("knows an application and the page of it that is open", () => {
    expect(areaOf("/apps/Field__c/o-Deal__c")).toEqual({
      kind: "application",
      application: "Field__c",
      entry: "o-Deal__c",
    });
    expect(areaOf("/apps/Field__c")).toEqual({ kind: "application", application: "Field__c", entry: undefined });
  });

  it("puts every page of the Home rail, and the pages below them, in Home", () => {
    for (const link of linksOf(homeRail(EVERYTHING))) {
      expect(areaOf(link.href), link.href).toEqual({ kind: "home" });
      expect(areaOf(`${link.href}/something`), link.href).toEqual({ kind: "home" });
    }
    expect(areaOf("/workflows/executions/abc")).toEqual({ kind: "home" });
    expect(areaOf("/setup/applications/new")).toEqual({ kind: "home" });
  });

  it("puts the object pages in Object settings, builders included", () => {
    expect(areaOf("/setup/objects")).toEqual({ kind: "objects" });
    expect(areaOf("/setup/objects/Account/layouts/Main")).toEqual({ kind: "objects" });
  });

  it("puts the console in the console", () => {
    expect(areaOf("/console")).toEqual({ kind: "console" });
    expect(areaOf("/console/plans")).toEqual({ kind: "console" });
  });

  it("names the pages that belong to no area, and says nothing for the rest", () => {
    expect(areaOf("/approvals")).toEqual({ kind: "neutral", name: "Approvals" });
    expect(areaOf("/approvals/delegations")).toEqual({ kind: "neutral", name: "Approvals" });
    expect(areaOf("/notifications/preferences")).toEqual({ kind: "neutral", name: "Notifications" });
    expect(areaOf("/records/Deal__c/new")).toEqual({ kind: "neutral", name: "Records" });
    expect(areaOf("/switch")).toEqual({ kind: "neutral", name: "Switch organization" });
    expect(areaOf("/")).toEqual({ kind: "neutral", name: null });
  });

  it("does not take the approvals inbox for the setup page of approval processes", () => {
    expect(areaOf("/approvals")).toEqual({ kind: "neutral", name: "Approvals" });
    expect(areaOf("/setup/approvals")).toEqual({ kind: "home" });
  });
});
