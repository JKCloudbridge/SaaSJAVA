import { describe, expect, it } from "vitest";
import { CATALOGUE, FIELD_TYPES, ORDER_FIELDS, WATCH } from "@/test/workflows";
import {
  actionHints,
  addAssignment,
  assignedObjectOf,
  changeAction,
  changeAssignment,
  changeSetting,
  definitionOf,
  draftFromView,
  emptyDraft,
  freshActionName,
  humanize,
  insertAction,
  moveActionBefore,
  newAction,
  nudgeAction,
  objectsNamedBy,
  removeAction,
  removeAssignment,
  settableFields,
  snapshotOf,
  triggerHints,
  triggerSpec,
  triggerText,
  unfinishedActions,
} from "./workflowModel";

const MAKE = CATALOGUE.actionTypes[1]!;
const SET = CATALOGUE.actionTypes[0]!;
const TELL = CATALOGUE.actionTypes[2]!;

describe("the draft of a workflow", () => {
  it("reads a workflow the API gave and makes the same request back", () => {
    const draft = draftFromView(WATCH);

    expect(draft.name).toBe("Watch__c");
    expect(draft.actions[0]?.assignments.map((one) => [one.field, one.mode, one.value, one.fromField])).toEqual([
      ["note__c", "value", "seen", ""],
      ["status__c", "copy", "", "note__c"],
    ]);
    const request = definitionOf(draft, CATALOGUE);
    expect(request.trigger).toEqual({ kind: "WHEN_FIELD_MOVES", fields: ["status__c"] });
    expect(request.condition).toBe("quantity__c > 1");
    expect(request.actions[0]).toEqual({
      type: "SET_FIELDS",
      name: "Mark",
      settings: {},
      assignments: [
        { field: "note__c", value: "seen" },
        { field: "status__c", fromField: "note__c" },
      ],
    });
    expect(request.actions[1]).toEqual({ type: "SIGNAL", name: "Tell", settings: { event: "order.seen" } });
  });

  it("starts a new workflow with the first kind of trigger the API offers, switched on", () => {
    const draft = emptyDraft(CATALOGUE);

    expect(draft).toMatchObject({ name: "", active: true, condition: "", actions: [] });
    expect(draft.trigger.kind).toBe("WHEN_MADE");
  });

  it("sends only the properties the kind of trigger uses, as the API says it, and leaves empty ones out", () => {
    const kind = CATALOGUE.triggerKinds[2];
    const trigger = {
      kind: "EVERY_MORNING",
      fields: ["status__c"],
      field: "x",
      days: "3",
      timeZone: " Europe/Berlin ",
      time: "09:30",
      dayOfWeek: "MONDAY",
      at: "2030-01-01T10:00",
      process: "",
      outcome: "",
      selection: { op: "EQ", field: "status__c", values: ["Open"] },
    };

    expect(triggerSpec(trigger, kind)).toEqual({
      kind: "EVERY_MORNING",
      timeZone: "Europe/Berlin",
      time: "09:30",
      selection: { op: "EQ", field: "status__c", values: ["Open"] },
    });
    expect(triggerSpec({ ...trigger, timeZone: " ", time: "", selection: undefined }, kind)).toEqual({
      kind: "EVERY_MORNING",
    });
    expect(triggerSpec({ ...trigger, kind: "WHEN_MADE" }, CATALOGUE.triggerKinds[0])).toEqual({ kind: "WHEN_MADE" });
  });

  it("sends assignments only with a type the API says takes them, trims the name, keeps a value as typed", () => {
    const withSettings = { ...newAction("MAKE_RECORD", []), name: " Make ", settings: { target: " Task__c ", linkField: "" } };
    const set = changeAssignmentValue(addAssignment([withSettings], 0, "title__c"), "  spaced  ");

    const request = definitionOf({ ...emptyDraft(CATALOGUE), actions: set }, CATALOGUE);

    expect(request.actions[0]).toEqual({
      type: "MAKE_RECORD",
      name: "Make",
      settings: { target: "Task__c" },
      assignments: [{ field: "title__c", value: "  spaced  " }],
    });
    const mail = { ...newAction("TELL_SOMEONE", []), settings: { who: "owner__c", which: "PLAIN_NOTICE" }, assignments: set[0]!.assignments };
    expect(definitionOf({ ...emptyDraft(CATALOGUE), actions: [mail] }, CATALOGUE).actions[0]).not.toHaveProperty("assignments");
  });

  it("sends the attempts only when a number is given", () => {
    const action = { ...newAction("SIGNAL", []), settings: { event: "a.b" } };

    const without = definitionOf({ ...emptyDraft(CATALOGUE), actions: [action] }, CATALOGUE).actions[0];
    const lowered = definitionOf({ ...emptyDraft(CATALOGUE), actions: [{ ...action, maxAttempts: "2" }] }, CATALOGUE).actions[0];

    expect(without).not.toHaveProperty("maxAttempts");
    expect(lowered).toHaveProperty("maxAttempts", 2);
  });

  it("notices a change by comparing with what was read, whatever the browser's own keys are", () => {
    const draft = draftFromView(WATCH);
    const same = draftFromView(WATCH);

    expect(snapshotOf(same)).toBe(snapshotOf(draft));
    expect(snapshotOf({ ...draft, label: "Other" })).not.toBe(snapshotOf(draft));
    expect(snapshotOf({ ...draft, actions: changeAction(draft.actions, 0, { name: "Renamed" }) })).not.toBe(snapshotOf(draft));
  });
});

function changeAssignmentValue(actions: ReturnType<typeof addAssignment>, value: string) {
  return changeAssignment(actions, 0, 0, { value });
}

describe("the actions of a draft", () => {
  it("names a new action so that no two share a name, and adds it where it is dropped", () => {
    const first = newAction("SIGNAL", []);
    const second = newAction("SIGNAL", [first]);
    const named = [first, { ...second, name: "Step3" }];

    expect([first.name, second.name]).toEqual(["Step1", "Step2"]);
    expect(freshActionName(named)).toBe("Step4");
    expect(new Set(named.map((one) => one.name)).size).toBe(2);
    expect(insertAction(named, newAction("SIGNAL", named), 0)).toHaveLength(3);
  });

  it("moves an action before another as a drop means it and by one place for the keyboard", () => {
    const list = ["a", "b", "c", "d"].map((name) => ({ ...newAction("SIGNAL", []), name }));

    expect(moveActionBefore(list, 0, 3).map((one) => one.name)).toEqual(["b", "c", "a", "d"]);
    expect(moveActionBefore(list, 3, 0).map((one) => one.name)).toEqual(["d", "a", "b", "c"]);
    expect(moveActionBefore(list, 1).map((one) => one.name)).toEqual(["a", "c", "d", "b"]);
    expect(nudgeAction(list, 2, -1).map((one) => one.name)).toEqual(["a", "c", "b", "d"]);
    expect(nudgeAction(list, 0, -1).map((one) => one.name)).toEqual(["a", "b", "c", "d"]);
    expect(removeAction(list, 1).map((one) => one.name)).toEqual(["a", "c", "d"]);
    expect(list.map((one) => one.name)).toEqual(["a", "b", "c", "d"]);
  });

  it("changes one setting or one assignment without touching the others", () => {
    const list = [newAction("MAKE_RECORD", []), newAction("SIGNAL", [])];
    const withSetting = changeSetting(list, 0, "target", "Task__c");
    const withRow = addAssignment(withSetting, 0, "a__c");
    const edited = changeAssignment(withRow, 0, 0, { mode: "copy", fromField: "b__c" });

    expect(edited[0]?.settings).toEqual({ target: "Task__c" });
    expect(edited[0]?.assignments[0]).toMatchObject({ field: "a__c", mode: "copy", fromField: "b__c" });
    expect(edited[1]).toBe(list[1]);
    expect(removeAssignment(edited, 0, 0)[0]?.assignments).toEqual([]);
  });

  it("finds the object whose fields an action sets from what the catalogue says, not from a name it knows", () => {
    const make = { ...newAction("MAKE_RECORD", []), settings: { target: "Task__c" } };

    expect(assignedObjectOf(newAction("SET_FIELDS", []), SET, "Order__c")).toBe("Order__c");
    expect(assignedObjectOf(make, MAKE, "Order__c")).toBe("Task__c");
    expect(assignedObjectOf(newAction("MAKE_RECORD", []), MAKE, "Order__c")).toBeUndefined();
    expect(assignedObjectOf(newAction("TELL_SOMEONE", []), TELL, "Order__c")).toBeUndefined();
    expect(objectsNamedBy(make, MAKE)).toEqual(["Task__c"]);
    expect(objectsNamedBy(newAction("SET_FIELDS", []), SET)).toEqual([]);
  });
});

describe("the hints while building", () => {
  it("says what an action still needs from the settings the catalogue calls required", () => {
    const action = newAction("TELL_SOMEONE", []);

    expect(actionHints(action, TELL)).toEqual(["Needs who.", "Needs which."]);
    expect(actionHints({ ...action, settings: { who: "owner__c", which: "PLAIN_NOTICE" } }, TELL)).toEqual([]);
  });

  it("says which assignment is not finished", () => {
    const action = changeAssignment(addAssignment(addAssignment([newAction("SET_FIELDS", [])], 0), 0, "a__c"), 0, 1, {
      mode: "copy",
    })[0]!;

    expect(actionHints(action, SET)).toEqual(["Field 1 has no field chosen.", "a__c has no field to copy from."]);
  });

  it("counts the actions that are not finished and keeps a name from being empty", () => {
    const draft = {
      ...emptyDraft(CATALOGUE),
      actions: [
        { ...newAction("SIGNAL", []), settings: { event: "a.b" } },
        newAction("SIGNAL", []),
        { ...newAction("SIGNAL", []), settings: { event: "a.b" }, name: " " },
        { ...newAction("GONE_TYPE", []), settings: {} },
      ],
    };

    expect(unfinishedActions(draft, CATALOGUE)).toEqual([1, 2, 3]);
  });

  it("asks for what the kind of trigger uses and is silent about the rest", () => {
    const fields = CATALOGUE.triggerKinds[1];

    expect(triggerHints({ ...emptyDraft(CATALOGUE).trigger, kind: "WHEN_FIELD_MOVES" }, fields)).toEqual([
      "Choose at least one field.",
    ]);
    expect(triggerHints({ ...emptyDraft(CATALOGUE).trigger, kind: "WHEN_FIELD_MOVES", fields: ["a__c"] }, fields)).toEqual([]);
    expect(triggerHints(emptyDraft(CATALOGUE).trigger, CATALOGUE.triggerKinds[0])).toEqual([]);
    expect(triggerHints(emptyDraft(CATALOGUE).trigger, undefined)).toEqual(["Choose when the workflow starts."]);
  });
});

describe("what the page offers", () => {
  it("offers the fields people can write, by the API's own word for a calculated type", () => {
    const names = settableFields(ORDER_FIELDS as never, FIELD_TYPES as never).map((one) => one.apiName);

    expect(names).toEqual(["status__c", "note__c", "quantity__c"]);
  });

  it("puts a code in words and a kind of trigger in the catalogue's sentence", () => {
    expect(humanize("UPDATE_RECORD")).toBe("Update record");
    expect(humanize("")).toBe("");
    expect(triggerText({ kind: "EVERY_MORNING" }, CATALOGUE)).toBe(CATALOGUE.triggerKinds[2]?.summary);
    expect(triggerText({ kind: "UNHEARD_OF" }, CATALOGUE)).toBe("Unheard of");
  });
});
