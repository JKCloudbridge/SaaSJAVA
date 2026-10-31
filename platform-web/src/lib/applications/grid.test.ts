import { describe, expect, it } from "vitest";
import type { MyApplication, NavigationEntry } from "@/lib/applications/entries";
import {
  filterApplications,
  groupSlots,
  groupsOf,
  readRecent,
  recentApplications,
  rememberRecent,
  resultsLine,
  showsComingSoon,
  tabsLine,
} from "./grid";

function entry(label: string, pageKey: string, placeholder = false): NavigationEntry {
  return { type: "PLATFORM", label, pageKey, available: true, placeholder: placeholder ? true : undefined };
}

function application(
  apiName: string,
  label: string,
  group: string | undefined,
  entries: NavigationEntry[],
): MyApplication {
  return { apiName, label, group, items: entries };
}

const home = entry("Home", "home");
const APPS: MyApplication[] = [
  application("quality", "Quality Management System", "Leadership", [
    home,
    entry("Document Control", "templates"),
    entry("Management Review", "management-review", true),
  ]),
  application("marketing", "Marketing - New Business Development", "Operations", [
    home,
    entry("Enquiry Review", "enquiry-review", true),
  ]),
  application("stores", "Stores", "Supply chain", [home, entry("FIFO", "fifo", true), entry("Shelf Life", "shelf", true)]),
  application("mine", "My own", undefined, [entry("Own page", "own-page")]),
];

class MemoryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

describe("groups", () => {
  it("lists each group once, in the order the API first names it, and none for an application without one", () => {
    expect(groupsOf(APPS)).toEqual(["Leadership", "Operations", "Supply chain"]);
  });

  it("gives each group a colour slot by that order and wraps after six", () => {
    const many = Array.from({ length: 8 }, (_, n) => application(`a${n}`, `A${n}`, `Group ${n}`, [home]));
    const slots = groupSlots(many);

    expect(slots.get("Group 0")).toBe(1);
    expect(slots.get("Group 5")).toBe(6);
    expect(slots.get("Group 6")).toBe(1);
  });
});

describe("search and the group chips", () => {
  it("keeps every application, in the API's order, when nothing is asked", () => {
    expect(filterApplications(APPS, "", null).map((a) => a.apiName)).toEqual(["quality", "marketing", "stores", "mine"]);
  });

  it("matches the name, the group and the names of the tabs, ignoring case and accents", () => {
    expect(filterApplications(APPS, "QUALITY", null).map((a) => a.apiName)).toEqual(["quality"]);
    expect(filterApplications(APPS, "supply", null).map((a) => a.apiName)).toEqual(["stores"]);
    expect(filterApplications(APPS, "enquiry", null).map((a) => a.apiName)).toEqual(["marketing"]);
    expect(filterApplications(APPS, "fífo", null).map((a) => a.apiName)).toEqual(["stores"]);
  });

  it("needs every word typed to be found", () => {
    expect(filterApplications(APPS, "marketing enquiry", null)).toHaveLength(1);
    expect(filterApplications(APPS, "marketing fifo", null)).toHaveLength(0);
  });

  it("narrows to one group and combines with the search", () => {
    expect(filterApplications(APPS, "", "Operations").map((a) => a.apiName)).toEqual(["marketing"]);
    expect(filterApplications(APPS, "stores", "Operations")).toEqual([]);
  });
});

describe("the words on a tile", () => {
  it("counts the tabs, Home included, and says tab for one", () => {
    expect(tabsLine(APPS[0]!)).toBe("3 tabs");
    expect(tabsLine(APPS[3]!)).toBe("1 tab");
  });

  it("tags an application whose every function is only announced, and no other", () => {
    expect(showsComingSoon(APPS[0]!)).toBe(false);
    expect(showsComingSoon(APPS[1]!)).toBe(true);
    expect(showsComingSoon(APPS[2]!)).toBe(true);
    expect(showsComingSoon(APPS[3]!)).toBe(false);
  });

  it("tags a shipped application that has nothing but its Home, and not an application without a Home (FA13A)", () => {
    const tile = application("tile", "Tile only", "Operations", [home]);
    expect(showsComingSoon(tile)).toBe(true);
    expect(tabsLine(tile)).toBe("1 tab");
    expect(showsComingSoon({ ...APPS[3]!, items: [] })).toBe(false);
  });

  it("says how many are shown after a search", () => {
    expect(resultsLine(4, 4)).toBe("4 applications");
    expect(resultsLine(1, 1)).toBe("1 application");
    expect(resultsLine(2, 16)).toBe("Showing 2 of 16 applications");
  });
});

describe("recent applications", () => {
  it("remembers the last three, newest first, each once", () => {
    const storage = new MemoryStorage();
    for (const name of ["a", "b", "c", "a", "d"]) {
      rememberRecent(name, storage);
    }

    expect(readRecent(storage)).toEqual(["d", "a", "c"]);
  });

  it("forgets what the person no longer has", () => {
    expect(recentApplications(APPS, ["gone", "stores", "quality"]).map((a) => a.apiName)).toEqual([
      "stores",
      "quality",
    ]);
  });

  it("survives a storage that holds nonsense or cannot be used", () => {
    const broken = { getItem: () => "{not json", setItem: () => undefined };
    const blocked = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    };

    expect(readRecent(broken)).toEqual([]);
    expect(readRecent(blocked)).toEqual([]);
    expect(() => rememberRecent("a", blocked)).not.toThrow();
    expect(readRecent(null)).toEqual([]);
  });
});
