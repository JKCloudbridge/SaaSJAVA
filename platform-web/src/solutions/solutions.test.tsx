import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { findPlatformPage, SOLUTIONS } from "./index";
import { PAGE_KEYS } from "./inspection/registry";
import { TemplatesPage } from "./inspection/TemplatesPage";

describe("the pages a shipped solution registers", () => {
  it("finds the page a key names inside its application", () => {
    expect(findPlatformPage("inspection", "templates")).toBe(TemplatesPage);
  });

  it("finds nothing for a key, an application or an empty key it does not know", () => {
    expect(findPlatformPage("inspection", "elsewhere")).toBeUndefined();
    expect(findPlatformPage("Field__c", "templates")).toBeUndefined();
    expect(findPlatformPage("inspection", "")).toBeUndefined();
    expect(findPlatformPage("inspection", null)).toBeUndefined();
    expect(findPlatformPage("inspection", undefined)).toBeUndefined();
  });

  it("does not take a property every object has for a page", () => {
    expect(findPlatformPage("inspection", "constructor")).toBeUndefined();
    expect(findPlatformPage("inspection", "toString")).toBeUndefined();
    expect(findPlatformPage("inspection", "__proto__")).toBeUndefined();
  });

  it("registers exactly the keys it lists, one solution per application", () => {
    const inspection = SOLUTIONS.find((solution) => solution.application === "inspection");
    expect(Object.keys(inspection?.pages ?? {}).sort()).toEqual([...PAGE_KEYS].sort());
    expect(new Set(SOLUTIONS.map((solution) => solution.application)).size).toBe(SOLUTIONS.length);
  });

  it("registers every page key the bundle that ships the application names, except Home and the announced ones", () => {
    // The bundle is a file of the backend; reading it keeps the two sides from drifting apart. A placeholder entry has
    // no page (the API marks it and the browser draws the "being prepared" page), and Home may fall back to the
    // generic overview, so neither needs a registered page.
    const bundle = readFileSync(
      resolve(__dirname, "../../../platform-app/src/main/resources/solutions/inspection/solution.yml"),
      "utf-8",
    );
    const entries = bundle.split(/^\s*- type:/m).slice(1);
    const named = entries
      .filter((entry) => !/^\s*placeholder:\s*true\s*$/m.test(entry))
      .map((entry) => /^\s*pageKey:\s*([a-z][a-z0-9-]*)\s*$/m.exec(entry)?.[1])
      .filter((key): key is string => key !== undefined && key !== "home");
    expect(named.length).toBeGreaterThan(0);
    const registered = [...PAGE_KEYS].filter((key) => key !== "home");
    expect([...new Set(named)].sort()).toEqual(registered.sort());
  });
});
