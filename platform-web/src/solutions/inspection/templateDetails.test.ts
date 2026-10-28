import { describe, expect, it } from "vitest";
import type { ArrangedSection } from "@/lib/records/columns";
import type { ObjectForm, RecordView } from "@/lib/records/types";
import type { TemplateLifecycleView } from "./api";
import { detailDisplay, revisionNumberOf, templateSections } from "./templateDetails";

const NONE = { state: "NONE", activeVersion: 0, approvedVersion: 0, inReviewVersion: 0, draftVersion: 0, retiredVersion: 0, highestVersion: 0 };
const view = (revision: string | undefined, lifecycle: Partial<typeof NONE> = {}, lastRevisedAt?: string) =>
  ({ revision, lastRevisedAt, lifecycle: { ...NONE, ...lifecycle } }) satisfies Pick<TemplateLifecycleView, "revision" | "lifecycle" | "lastRevisedAt">;

const NAMES = ["name", "level", "documentType", "category", "subCategory", "partCode", "planYear", "number", "revision", "retired", "lastRevisedAt", "createdAt", "owner"];
const form = (without: readonly string[] = []): ObjectForm =>
  ({
    objectApiName: "InspectionTemplate",
    layout: { source: "GENERATED", sections: [{ label: "Details", columns: 2, fields: ["number"] }] },
    fields: NAMES.filter((name) => !without.includes(name)).map((apiName) => ({
      apiName,
      label: apiName,
      type: "TEXT",
      kind: apiName === "createdAt" ? "SYSTEM" : "STANDARD",
    })),
  }) as unknown as ObjectForm;
const record = (fields: Record<string, unknown> = {}) => ({ id: "r1", fields }) as unknown as RecordView;

describe("the Revision number of a template (FA11A)", () => {
  it("uses the one the platform saved", () => {
    expect(revisionNumberOf(view("01", { activeVersion: 9 }))).toBe("01");
  });

  it("reads it from the sheet's Revision in use when none was saved, by the rule the Sheet tab uses", () => {
    expect(revisionNumberOf(view(undefined, { activeVersion: 1 }))).toBe("00");
    expect(revisionNumberOf(view(undefined, { activeVersion: 4 }))).toBe("03");
  });

  it("is nothing before a first Revision is in use", () => {
    expect(revisionNumberOf(view(undefined, { draftVersion: 1 }))).toBeUndefined();
    expect(revisionNumberOf(undefined)).toBeUndefined();
  });
});

describe("the words the Details tab works out (FA11A)", () => {
  it("gives the Revision number and the date and time of the Revision in use in the reader's time zone", () => {
    const display = detailDisplay(view("02", {}, "2026-10-26T08:00:00Z"), record({ lastRevisedAt: "2026-10-26T08:00:00Z" }));

    expect(display.revision).toBe("02");
    expect(display.lastRevisedAt).toBe(new Date("2026-10-26T08:00:00Z").toLocaleString());
  });

  it("gives nothing for what does not exist yet", () => {
    expect(detailDisplay(view(undefined), record())).toEqual({});
  });
});

describe("the sections of the Details tab (FA11A)", () => {
  const sectionsOf = (result: ObjectForm) => result.layout.sections as ArrangedSection[];

  it("reads in two named columns: Obsolete last on the right, the date of the Revision in System information", () => {
    const [details, system] = sectionsOf(templateSections(form(), record(), view("00", { activeVersion: 1 }), false));

    expect(details?.left).toEqual(["name", "level", "documentType", "category", "subCategory", "owner"]);
    expect(details?.fields).toEqual([...(details?.left ?? []), "number", "revision", "retired"]);
    expect(system?.fields).toEqual(["lastRevisedAt", "createdAt"]);
  });

  it("leaves out Part code and Plan year without a value when reading, and offers them when editing", () => {
    const reading = sectionsOf(templateSections(form(), record({ planYear: "2026" }), view("00", { activeVersion: 1 }), false))[0];
    expect(reading?.left).toContain("planYear");
    expect(reading?.left).not.toContain("partCode");

    const editing = sectionsOf(templateSections(form(), record(), view("00", { activeVersion: 1 }), true))[0];
    expect(editing?.left).toEqual(expect.arrayContaining(["partCode", "planYear"]));
  });

  it("leaves out the Revision number before a first Revision is in use, but keeps the field when editing", () => {
    expect(sectionsOf(templateSections(form(), record(), view(undefined, { draftVersion: 1 }), false))[0]?.fields).not.toContain("revision");
    expect(sectionsOf(templateSections(form(), record(), view(undefined, { draftVersion: 1 }), true))[0]?.fields).toContain("revision");
  });

  it("draws only fields the member's form has: a field the member cannot read does not exist", () => {
    const [details, system] = sectionsOf(templateSections(form(["revision", "retired", "lastRevisedAt"]), record(), view("01"), false));

    expect(details?.fields).toEqual([...(details?.left ?? []), "number"]);
    expect(system?.fields).toEqual(["createdAt"]);
  });
});
