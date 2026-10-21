import { describe, expect, it } from "vitest";
import {
  applicationHref,
  describeKind,
  entryHref,
  entrySlug,
  findApplication,
  findEntry,
  firstPage,
  isOpenable,
  type MyApplication,
  type NavigationEntry,
} from "./entries";

const OBJECT: NavigationEntry = {
  type: "OBJECT",
  label: "Deals",
  objectApiName: "Deal__c",
  available: true,
};
const PAGE: NavigationEntry = {
  type: "RECORD_PAGE",
  label: "Overview",
  objectApiName: "Deal__c",
  pageApiName: "Overview__c",
  available: true,
};
const CUSTOM: NavigationEntry = {
  type: "CUSTOM",
  label: "Inspections",
  tabApiName: "Inspections__c",
  customPageApiName: "Inspection__c",
  assetId: "11111111-2222-3333-4444-555555555555",
  available: true,
};
const PLATFORM: NavigationEntry = {
  type: "PLATFORM",
  label: "Templates",
  pageKey: "templates",
  available: true,
};
const LINK: NavigationEntry = { type: "LINK", label: "Members", path: "/members", available: true };
const HEADING: NavigationEntry = { type: "HEADING", label: "Work", available: true };

function app(...items: NavigationEntry[]): MyApplication {
  return { apiName: "Field__c", label: "Field", iconKey: "tool", items };
}

describe("addresses of an application and its pages", () => {
  it("makes the address of an application from its API name", () => {
    expect(applicationHref(app())).toBe("/apps/Field__c");
  });

  it("names a page by what kind of entry it is and the names the entry already has", () => {
    expect(entrySlug(OBJECT)).toBe("o-Deal__c");
    expect(entrySlug(PAGE)).toBe("p-Deal__c.Overview__c");
    expect(entrySlug(CUSTOM)).toBe("c-Inspections__c");
  });

  it("names a page the platform ships by its page key, and has no page for an entry without one", () => {
    expect(entrySlug(PLATFORM)).toBe("s-templates");
    expect(entrySlug({ ...PLATFORM, pageKey: undefined })).toBeNull();
    expect(entryHref({ apiName: "inspection" }, PLATFORM)).toBe("/apps/inspection/s-templates");
    expect(describeKind(PLATFORM)).toBe("page");
  });

  it("gives a heading and a link no page of their own", () => {
    expect(entrySlug(HEADING)).toBeNull();
    expect(entrySlug(LINK)).toBeNull();
  });

  it("does not mix up a name of one kind with another", () => {
    const asObject = { ...OBJECT, objectApiName: "c-Inspections__c" };

    expect(entrySlug(asObject)).not.toBe(entrySlug(CUSTOM));
  });

  it("leads a page entry to its address, a link to the path it holds, and a heading nowhere", () => {
    const application = app(OBJECT, CUSTOM, LINK, HEADING);

    expect(entryHref(application, OBJECT)).toBe("/apps/Field__c/o-Deal__c");
    expect(entryHref(application, CUSTOM)).toBe("/apps/Field__c/c-Inspections__c");
    expect(entryHref(application, LINK)).toBe("/members");
    expect(entryHref(application, HEADING)).toBeNull();
  });

  it("finds the entry an address names and says nothing when there is none", () => {
    const application = app(HEADING, OBJECT, CUSTOM);

    expect(findEntry(application, "c-Inspections__c")).toBe(CUSTOM);
    expect(findEntry(application, "o-Deal__c")).toBe(OBJECT);
    expect(findEntry(application, "o-Gone__c")).toBeUndefined();
    expect(findEntry(application, "")).toBeUndefined();
  });

  it("finds an application among the member's by its API name", () => {
    const list = [app(), { ...app(), apiName: "Other__c" }];

    expect(findApplication(list, "Other__c")?.apiName).toBe("Other__c");
    expect(findApplication(list, "Nothing__c")).toBeUndefined();
  });
});

describe("what an application opens on", () => {
  it("opens on the first entry that is a page of its own", () => {
    expect(firstPage(app(HEADING, LINK, CUSTOM, OBJECT))).toBe(CUSTOM);
  });

  it("falls back to a link only when there is no page of its own", () => {
    expect(firstPage(app(HEADING, LINK))).toBe(LINK);
  });

  it("skips an entry that cannot be opened", () => {
    const unavailable = { ...CUSTOM, available: false };

    expect(firstPage(app(unavailable, OBJECT))).toBe(OBJECT);
  });

  it("opens on nothing when there is nothing to open", () => {
    expect(firstPage(app())).toBeUndefined();
    expect(firstPage(app(HEADING))).toBeUndefined();
    expect(firstPage(app({ ...CUSTOM, available: false }))).toBeUndefined();
  });

  it("knows which entries can be opened", () => {
    expect(isOpenable(OBJECT)).toBe(true);
    expect(isOpenable(LINK)).toBe(true);
    expect(isOpenable(HEADING)).toBe(false);
    expect(isOpenable({ ...CUSTOM, available: false })).toBe(false);
  });

  it("has words for every kind of entry", () => {
    expect([OBJECT, PAGE, CUSTOM, LINK, HEADING].map(describeKind)).toEqual([
      "list of records",
      "record page",
      "custom page",
      "link",
      "heading",
    ]);
  });
});
