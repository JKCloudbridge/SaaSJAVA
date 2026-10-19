import { describe, expect, it } from "vitest";
import { CATALOGUE, PROCESS } from "@/test/approvals";
import {
  addApprover,
  addOutcomeField,
  approverSpec,
  changeApprover,
  changeOutcomeField,
  changeStep,
  definitionOf,
  draftFromView,
  emptyDraft,
  humanize,
  insertStep,
  moveStepBefore,
  newStep,
  nudgeStep,
  outcomesSpec,
  removeApprover,
  removeOutcomeField,
  removeStep,
  settableFields,
  snapshotOf,
  stepHints,
  unfinishedOutcomes,
  unfinishedSteps,
  userFields,
  valueNames,
} from "./approvalModel";

function withSteps(count: number) {
  let steps = emptyDraft(CATALOGUE).steps;
  for (let at = 0; at < count; at += 1) {
    steps = insertStep(steps, newStep(steps, CATALOGUE));
  }
  return steps;
}

describe("the approval model", () => {
  it("starts with no steps, switched on, and one empty list for every outcome the catalogue lists", () => {
    const draft = emptyDraft(CATALOGUE);

    expect(draft.steps).toEqual([]);
    expect(draft.active).toBe(true);
    expect(Object.keys(draft.outcomes)).toEqual(["APPROVED", "REJECTED", "RECALLED"]);
    expect(draft.fallback.kind).toBe("");
  });

  it("makes new steps with names that no other step has and the first completion the API lists", () => {
    const steps = withSteps(2);

    expect(steps.map((step) => step.name)).toEqual(["Step1", "Step2"]);
    expect(steps[0]?.completion).toBe("ANY");
    expect(newStep(changeStep(steps, 0, { name: "Step3" }), CATALOGUE).name).toBe("Step4");
  });

  it("moves, nudges and removes steps without touching the others", () => {
    const steps = withSteps(3);
    const names = (list: typeof steps) => list.map((step) => step.name);

    expect(names(moveStepBefore(steps, 2, 0))).toEqual(["Step3", "Step1", "Step2"]);
    expect(names(moveStepBefore(steps, 0))).toEqual(["Step2", "Step3", "Step1"]);
    expect(names(nudgeStep(steps, 1, -1))).toEqual(["Step2", "Step1", "Step3"]);
    expect(names(removeStep(steps, 1))).toEqual(["Step1", "Step3"]);
    expect(names(steps)).toEqual(["Step1", "Step2", "Step3"]);
  });

  it("adds, changes and removes the approver rules of one step only", () => {
    let steps = withSteps(2);
    steps = addApprover(steps, 0, "USER");
    steps = addApprover(steps, 0, "OWNER");
    steps = changeApprover(steps, 0, 0, { value: "u-1" });

    expect(steps[0]?.approvers.map((approver) => `${approver.kind}:${approver.value}`)).toEqual(["USER:u-1", "OWNER:"]);
    expect(steps[1]?.approvers).toEqual([]);
    expect(removeApprover(steps, 0, 0)[0]?.approvers).toHaveLength(1);
  });

  it("sends a value only for a kind that the catalogue says takes one", () => {
    const owner = CATALOGUE.approverKinds.find((one) => one.kind === "OWNER");
    const user = CATALOGUE.approverKinds.find((one) => one.kind === "USER");

    expect(approverSpec({ id: "a", kind: "OWNER", value: "leftover" }, owner)).toEqual({ kind: "OWNER" });
    expect(approverSpec({ id: "a", kind: "USER", value: " u-1 " }, user)).toEqual({ kind: "USER", value: "u-1" });
    expect(approverSpec({ id: "a", kind: "USER", value: "  " }, user)).toEqual({ kind: "USER" });
    expect(valueNames({ id: "a", kind: "RECORD_USER", value: "" }, CATALOGUE)).toBe("USER_FIELD");
    expect(valueNames({ id: "a", kind: "UNKNOWN", value: "" }, CATALOGUE)).toBe("NOTHING");
  });

  it("makes the request from the draft: trimmed, criteria only when written, the fallback only when chosen", () => {
    const draft = draftFromView(PROCESS, CATALOGUE);
    draft.entryCriteria = "  amount__c > 5 ";

    const request = definitionOf(draft, CATALOGUE);

    expect(request.entryCriteria).toBe("amount__c > 5");
    expect(request.steps.map((step) => step.name)).toEqual(["Manager", "Finance"]);
    expect(request.fallback).toBeUndefined();
    expect(definitionOf({ ...draft, entryCriteria: "" }, CATALOGUE).entryCriteria).toBeUndefined();
    expect(definitionOf({ ...draft, fallback: { kind: "USER", value: "u-9" } }, CATALOGUE).fallback).toEqual({
      kind: "USER",
      value: "u-9",
    });
  });

  it("reads the outcomes of the API's answer by the lower case code and sends them back the same way", () => {
    const draft = draftFromView(PROCESS, CATALOGUE);

    expect(draft.outcomes.APPROVED).toMatchObject([{ field: "status__c", value: "Approved" }]);
    expect(draft.outcomes.REJECTED).toEqual([]);
    expect(outcomesSpec(draft, CATALOGUE)).toEqual({
      approved: [{ field: "status__c", value: "Approved" }],
      rejected: [],
      recalled: [],
    });
  });

  it("edits the fields an outcome sets and counts the ones that are not finished", () => {
    let outcomes = emptyDraft(CATALOGUE).outcomes;
    outcomes = addOutcomeField(outcomes, "APPROVED");
    outcomes = changeOutcomeField(outcomes, "APPROVED", 0, { field: "status__c" });

    expect(unfinishedOutcomes({ ...emptyDraft(CATALOGUE), outcomes })).toBe(1);
    outcomes = changeOutcomeField(outcomes, "APPROVED", 0, { value: "Approved" });
    expect(unfinishedOutcomes({ ...emptyDraft(CATALOGUE), outcomes })).toBe(0);
    expect(removeOutcomeField(outcomes, "APPROVED", 0).APPROVED).toEqual([]);
  });

  it("gives hints, never verdicts, about a step that is not finished", () => {
    let steps = withSteps(1);
    expect(stepHints(steps[0]!, CATALOGUE)).toEqual(["Give the step a label.", "Say who is asked."]);

    steps = changeStep(steps, 0, { label: "Manager" });
    steps = addApprover(steps, 0, "USER");
    expect(stepHints(steps[0]!, CATALOGUE)).toEqual(["Rule 1 needs a choice."]);
    expect(unfinishedSteps({ ...emptyDraft(CATALOGUE), steps }, CATALOGUE)).toEqual([0]);

    steps = changeApprover(steps, 0, 0, { value: "u-1" });
    expect(stepHints(steps[0]!, CATALOGUE)).toEqual([]);
    expect(unfinishedSteps({ ...emptyDraft(CATALOGUE), steps }, CATALOGUE)).toEqual([]);
  });

  it("changes its snapshot when the draft changes and not when only a browser identifier does", () => {
    const first = draftFromView(PROCESS, CATALOGUE);
    const again = draftFromView(PROCESS, CATALOGUE);

    expect(snapshotOf(first)).toBe(snapshotOf(again));
    expect(snapshotOf({ ...first, label: "Other" })).not.toBe(snapshotOf(first));
    expect(snapshotOf({ ...first, steps: changeStep(first.steps, 0, { label: "Boss" }) })).not.toBe(snapshotOf(first));
  });

  it("offers only the user fields and the settable fields, and makes a code readable", () => {
    const field = (apiName: string, type: string, extra: Record<string, unknown> = {}) =>
      ({ apiName, type, kind: "CUSTOM", retired: false, ...extra }) as never;
    const fields = [
      field("manager__c", "LOOKUP", { settings: { targetObject: "User" } }),
      field("vendor__c", "LOOKUP", { settings: { targetObject: "Vendor__c" } }),
      field("old__c", "LOOKUP", { settings: { targetObject: "User" }, retired: true }),
      field("total__c", "FORMULA"),
      field("id", "TEXT", { kind: "SYSTEM" }),
      field("note__c", "TEXT"),
    ];

    expect(userFields(fields).map((one) => one.apiName)).toEqual(["manager__c"]);
    expect(settableFields(fields, new Set(["FORMULA"])).map((one) => one.apiName)).toEqual([
      "manager__c",
      "vendor__c",
      "note__c",
    ]);
    expect(humanize("OWNER_ROLE_MANAGER")).toBe("Owner role manager");
    expect(humanize("")).toBe("");
  });
});

describe("the time a step may wait", () => {
  it("starts empty, which means never", () => {
    const draft = emptyDraft(CATALOGUE);

    expect(draft.remindAfterHours).toBe("");
    expect(draft.escalateAfterHours).toBe("");
    const request = definitionOf(draft, CATALOGUE);
    expect(request).not.toHaveProperty("remindAfterHours");
    expect(request).not.toHaveProperty("escalateAfterHours");
  });

  it("is read from the process and sent as numbers, and only when it is typed", () => {
    const draft = draftFromView({ ...PROCESS, remindAfterHours: 24, escalateAfterHours: 72 }, CATALOGUE);

    expect(draft.remindAfterHours).toBe("24");
    expect(draft.escalateAfterHours).toBe("72");
    const request = definitionOf(draft, CATALOGUE);
    expect(request).toMatchObject({ remindAfterHours: 24, escalateAfterHours: 72 });

    const cleared = definitionOf({ ...draft, remindAfterHours: " ", escalateAfterHours: "" }, CATALOGUE);
    expect(cleared).not.toHaveProperty("remindAfterHours");
    expect(cleared).not.toHaveProperty("escalateAfterHours");
  });

  it("makes the draft unsaved when either time changes", () => {
    const draft = draftFromView(PROCESS, CATALOGUE);

    expect(snapshotOf({ ...draft, remindAfterHours: "12" })).not.toBe(snapshotOf(draft));
    expect(snapshotOf({ ...draft, escalateAfterHours: "48" })).not.toBe(snapshotOf(draft));
  });
});
