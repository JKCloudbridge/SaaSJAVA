import { describe, expect, it } from "vitest";
import { DEAL_FORM, deal } from "@/test/records";
import { compareChanges, needsChoice } from "./conflict";

const fields = DEAL_FORM.fields;

describe("recovering from a record that changed while it was being edited", () => {
  it("keeps what the person typed wherever the other change did not touch the same field", () => {
    const base = deal("r1", { name__c: "Acme", stage__c: "OPEN" });
    const latest = deal("r1", { name__c: "Acme", stage__c: "WON" }, { version: 4 });

    const conflict = compareChanges(fields, base, latest, { name__c: "Acme Ltd" });

    expect(conflict.reapply).toEqual(["name__c"]);
    expect(conflict.clashes).toEqual([]);
    expect(conflict.theirChanges).toEqual(["stage__c"]);
    expect(needsChoice(conflict)).toBe(false);
  });

  it("asks when both changed the same field to different things", () => {
    const base = deal("r1", { stage__c: "OPEN" });
    const latest = deal("r1", { stage__c: "WON" }, { version: 4 });

    const conflict = compareChanges(fields, base, latest, { stage__c: "OPEN-ISH" });

    expect(conflict.clashes).toEqual([{ field: "stage__c", mine: "OPEN-ISH", theirs: "WON" }]);
    expect(conflict.reapply).toEqual([]);
    expect(needsChoice(conflict)).toBe(true);
  });

  it("does not ask when both changed a field to the same thing", () => {
    const base = deal("r1", { stage__c: "OPEN" });
    const latest = deal("r1", { stage__c: "WON" }, { version: 4 });

    const conflict = compareChanges(fields, base, latest, { stage__c: "WON" });

    expect(conflict.clashes).toEqual([]);
    expect(conflict.reapply).toEqual(["stage__c"]);
  });

  it("does not list what the platform calculates or a system field as the other person's change", () => {
    const base = deal("r1", { margin__c: 1 });
    const latest = deal("r1", { margin__c: 2 }, { version: 4, updatedAt: "2031-01-01T00:00:00Z" });

    const conflict = compareChanges(fields, base, latest, {});

    expect(conflict.theirChanges).toEqual([]);
  });

  it("sees a value that was cleared by the other change as a change", () => {
    const base = deal("r1", { notes__c: "text" });
    const latest = deal("r1", {}, { version: 4 });
    const withoutNotes = { ...latest, fields: { ...latest.fields } };
    delete (withoutNotes.fields as Record<string, unknown>).notes__c;

    expect(compareChanges(fields, base, withoutNotes, { name__c: "x" }).theirChanges).toContain("notes__c");
  });
});
