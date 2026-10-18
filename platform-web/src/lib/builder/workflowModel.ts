import type { components } from "@/lib/api/generated/schema";
import { uid } from "./ids";
import { insertAt, move, removeAt, updateAt } from "./reorder";

type WorkflowView = components["schemas"]["WorkflowView"];
type TriggerSpec = components["schemas"]["WorkflowTriggerSpec"];
type ActionSpec = components["schemas"]["WorkflowActionSpec"];
type AssignmentSpec = components["schemas"]["WorkflowAssignmentSpec"];
type QueryFilterNode = components["schemas"]["QueryFilterNode"];
type FieldView = components["schemas"]["FieldView"];
type FieldTypeView = components["schemas"]["FieldTypeView"];

export type Catalogue = components["schemas"]["WorkflowCatalogueView"];
export type CatalogueActionType = components["schemas"]["CatalogueActionType"];
export type CatalogueTriggerKind = components["schemas"]["CatalogueTriggerKind"];

/*
 * The model of the workflow builder (Sprint 23, ADR-0132, ADR-0138). Pure: no React, no network. A draft is what the
 * person is editing; the API's shape is made from it only when they save. The model holds no list of action types,
 * setting names, trigger kinds or limits: every one of them is read from the catalogue the API gives. What it says about
 * a draft is a hint (something is not finished yet); whether the workflow is acceptable is the API's answer.
 */

/** One field an action sets, as the builder holds it. `id` identifies it in the browser only. */
export interface AssignmentDraft {
  id: string;
  field: string;
  /** Whether the value is written here or copied from a field of the record that starts the workflow. */
  mode: "value" | "copy";
  value: string;
  fromField: string;
}

/** One action, as the builder holds it. */
export interface ActionDraft {
  id: string;
  type: string;
  name: string;
  settings: Record<string, string>;
  assignments: AssignmentDraft[];
  /** Kept as typed; empty means the platform's number of attempts. */
  maxAttempts: string;
}

/** The trigger, as the builder holds it: every property the kinds use, as text; the kind decides which are sent. */
export interface TriggerDraft {
  kind: string;
  fields: string[];
  field: string;
  days: string;
  timeZone: string;
  time: string;
  dayOfWeek: string;
  at: string;
  /** For the kind that starts when an approval ends: the process (empty: any) and the outcome (empty: any). */
  process: string;
  outcome: string;
  selection?: QueryFilterNode;
}

export interface WorkflowDraft {
  name: string;
  label: string;
  description: string;
  active: boolean;
  trigger: TriggerDraft;
  condition: string;
  actions: ActionDraft[];
}

// ---- making a draft ----

export function emptyTrigger(kind = ""): TriggerDraft {
  return {
    kind,
    fields: [],
    field: "",
    days: "",
    timeZone: "",
    time: "",
    dayOfWeek: "",
    at: "",
    process: "",
    outcome: "",
  };
}

/** A new workflow: no name yet, switched on, the first kind of trigger the API offers. */
export function emptyDraft(catalogue: Catalogue): WorkflowDraft {
  return {
    name: "",
    label: "",
    description: "",
    active: true,
    trigger: emptyTrigger(catalogue.triggerKinds[0]?.kind ?? ""),
    condition: "",
    actions: [],
  };
}

export function assignmentFromSpec(spec: AssignmentSpec): AssignmentDraft {
  const copy = spec.fromField !== undefined && spec.fromField !== "";
  return {
    id: uid(),
    field: spec.field,
    mode: copy ? "copy" : "value",
    value: copy ? "" : (spec.value ?? ""),
    fromField: spec.fromField ?? "",
  };
}

export function actionFromSpec(spec: ActionSpec): ActionDraft {
  return {
    id: uid(),
    type: spec.type,
    name: spec.name,
    settings: { ...(spec.settings ?? {}) },
    assignments: (spec.assignments ?? []).map(assignmentFromSpec),
    maxAttempts: spec.maxAttempts === undefined ? "" : String(spec.maxAttempts),
  };
}

export function triggerFromSpec(spec: TriggerSpec): TriggerDraft {
  return {
    kind: spec.kind,
    fields: [...(spec.fields ?? [])],
    field: spec.field ?? "",
    days: spec.days === undefined ? "" : String(spec.days),
    timeZone: spec.timeZone ?? "",
    time: spec.time ?? "",
    dayOfWeek: spec.dayOfWeek ?? "",
    at: spec.at ?? "",
    process: spec.process ?? "",
    outcome: spec.outcome ?? "",
    selection: spec.selection,
  };
}

export function draftFromView(view: WorkflowView): WorkflowDraft {
  return {
    name: view.apiName,
    label: view.label,
    description: view.description,
    active: view.active,
    trigger: triggerFromSpec(view.trigger),
    condition: view.condition ?? "",
    actions: view.actions.map(actionFromSpec),
  };
}

// ---- making the request ----

/** The trigger to send: the kind and only the properties that kind uses (the API's own list), left out when empty. */
export function triggerSpec(trigger: TriggerDraft, kind: CatalogueTriggerKind | undefined): TriggerSpec {
  const uses = new Set(kind?.properties ?? []);
  const spec: TriggerSpec = { kind: trigger.kind };
  if (uses.has("fields") && trigger.fields.length > 0) {
    spec.fields = trigger.fields;
  }
  if (uses.has("field") && trigger.field !== "") {
    spec.field = trigger.field;
  }
  if (uses.has("days") && trigger.days.trim() !== "") {
    spec.days = Number(trigger.days);
  }
  if (uses.has("timeZone") && trigger.timeZone.trim() !== "") {
    spec.timeZone = trigger.timeZone.trim();
  }
  if (uses.has("time") && trigger.time !== "") {
    spec.time = trigger.time;
  }
  if (uses.has("dayOfWeek") && trigger.dayOfWeek !== "") {
    spec.dayOfWeek = trigger.dayOfWeek;
  }
  if (uses.has("at") && trigger.at !== "") {
    spec.at = trigger.at;
  }
  if (uses.has("process") && trigger.process.trim() !== "") {
    spec.process = trigger.process.trim();
  }
  if (uses.has("outcome") && trigger.outcome.trim() !== "") {
    spec.outcome = trigger.outcome.trim();
  }
  if (uses.has("selection") && trigger.selection) {
    spec.selection = trigger.selection;
  }
  return spec;
}

/** The action to send. Assignments go only with a type that takes them; a literal is sent as typed, never trimmed. */
export function actionSpec(action: ActionDraft, type: CatalogueActionType | undefined): ActionSpec {
  const settings: Record<string, string> = {};
  for (const setting of type?.settings ?? []) {
    const value = (action.settings[setting.key] ?? "").trim();
    if (value !== "") {
      settings[setting.key] = value;
    }
  }
  const spec: ActionSpec = { type: action.type, name: action.name.trim(), settings };
  if (type !== undefined && type.assignments !== "NONE") {
    spec.assignments = action.assignments.map(
      (assignment): AssignmentSpec =>
        assignment.mode === "copy"
          ? { field: assignment.field, fromField: assignment.fromField }
          : { field: assignment.field, value: assignment.value },
    );
  }
  if (action.maxAttempts.trim() !== "") {
    spec.maxAttempts = Number(action.maxAttempts);
  }
  return spec;
}

/** The parts of the request every save sends, whether it makes the workflow or changes it. */
export function definitionOf(draft: WorkflowDraft, catalogue: Catalogue) {
  const kind = catalogue.triggerKinds.find((one) => one.kind === draft.trigger.kind);
  const condition = draft.condition.trim();
  return {
    trigger: triggerSpec(draft.trigger, kind),
    ...(condition === "" ? {} : { condition }),
    actions: draft.actions.map((action) =>
      actionSpec(
        action,
        catalogue.actionTypes.find((one) => one.type === action.type),
      ),
    ),
  };
}

/** A string that changes when the draft does, for the "unsaved changes" badge: the draft is compared with what was read. */
export function snapshotOf(draft: WorkflowDraft): string {
  return JSON.stringify({
    ...draft,
    actions: draft.actions.map((action) => ({
      type: action.type,
      name: action.name,
      settings: action.settings,
      maxAttempts: action.maxAttempts,
      assignments: action.assignments.map((assignment) => ({
        field: assignment.field,
        mode: assignment.mode,
        value: assignment.value,
        fromField: assignment.fromField,
      })),
    })),
  });
}

// ---- the actions ----

/** A name for a new action that no other action of the workflow has: Step1, Step2, ... */
export function freshActionName(actions: readonly ActionDraft[]): string {
  const taken = new Set(actions.map((action) => action.name.toLowerCase()));
  for (let number = actions.length + 1; ; number += 1) {
    const name = `Step${number}`;
    if (!taken.has(name.toLowerCase())) {
      return name;
    }
  }
}

export function newAction(type: string, actions: readonly ActionDraft[]): ActionDraft {
  return { id: uid(), type, name: freshActionName(actions), settings: {}, assignments: [], maxAttempts: "" };
}

export function insertAction(actions: readonly ActionDraft[], action: ActionDraft, before?: number): ActionDraft[] {
  return insertAt(actions, action, before);
}

/** Moves the action at `from` before the action that was at `before` (or to the end), counting the list as the drop saw it. */
export function moveActionBefore(actions: readonly ActionDraft[], from: number, before?: number): ActionDraft[] {
  const at = before === undefined ? actions.length : before;
  const to = from < at ? at - 1 : at;
  return move(actions, from, Math.min(Math.max(to, 0), actions.length - 1));
}

export function nudgeAction(actions: readonly ActionDraft[], index: number, delta: number): ActionDraft[] {
  return move(actions, index, index + delta);
}

export function removeAction(actions: readonly ActionDraft[], index: number): ActionDraft[] {
  return removeAt(actions, index);
}

export function changeAction(
  actions: readonly ActionDraft[],
  index: number,
  change: Partial<Omit<ActionDraft, "id" | "settings" | "assignments">>,
): ActionDraft[] {
  return updateAt(actions, index, (action) => ({ ...action, ...change }));
}

export function changeSetting(actions: readonly ActionDraft[], index: number, key: string, value: string): ActionDraft[] {
  return updateAt(actions, index, (action) => ({ ...action, settings: { ...action.settings, [key]: value } }));
}

// ---- the assignments of an action ----

export function addAssignment(actions: readonly ActionDraft[], index: number, field = ""): ActionDraft[] {
  return updateAt(actions, index, (action) => ({
    ...action,
    assignments: [...action.assignments, { id: uid(), field, mode: "value", value: "", fromField: "" }],
  }));
}

export function changeAssignment(
  actions: readonly ActionDraft[],
  index: number,
  assignmentIndex: number,
  change: Partial<Omit<AssignmentDraft, "id">>,
): ActionDraft[] {
  return updateAt(actions, index, (action) => ({
    ...action,
    assignments: updateAt(action.assignments, assignmentIndex, (assignment) => ({ ...assignment, ...change })),
  }));
}

export function removeAssignment(actions: readonly ActionDraft[], index: number, assignmentIndex: number): ActionDraft[] {
  return updateAt(actions, index, (action) => ({
    ...action,
    assignments: removeAt(action.assignments, assignmentIndex),
  }));
}

/**
 * The object whose fields an action sets, as the catalogue says: the object of the workflow, the object a setting of the
 * action names, or none (the type sets no fields).
 */
export function assignedObjectOf(
  action: ActionDraft,
  type: CatalogueActionType | undefined,
  workflowObject: string,
): string | undefined {
  if (type === undefined) {
    return undefined;
  }
  if (type.assignments === "TRIGGERING_OBJECT") {
    return workflowObject;
  }
  if (type.assignments === "NAMED_OBJECT" && type.assignedObject) {
    const named = (action.settings[type.assignedObject] ?? "").trim();
    return named === "" ? undefined : named;
  }
  return undefined;
}

/** The objects an action refers to, so the builder can read their fields: its own assigned object and every named object. */
export function objectsNamedBy(action: ActionDraft, type: CatalogueActionType | undefined): string[] {
  const names = (type?.settings ?? [])
    .filter((setting) => setting.refersTo === "OBJECT")
    .map((setting) => (action.settings[setting.key] ?? "").trim())
    .filter((name) => name !== "");
  return [...new Set(names)];
}

// ---- hints ----

/** What is not finished in one action: a hint while building. The API checks the saved workflow and says what is wrong. */
export function actionHints(action: ActionDraft, type: CatalogueActionType | undefined): string[] {
  const hints: string[] = [];
  for (const setting of type?.settings ?? []) {
    if (setting.required && (action.settings[setting.key] ?? "").trim() === "") {
      hints.push(`Needs ${setting.key}.`);
    }
  }
  if (type !== undefined && type.assignments !== "NONE") {
    action.assignments.forEach((assignment, at) => {
      if (assignment.field === "") {
        hints.push(`Field ${at + 1} has no field chosen.`);
      } else if (assignment.mode === "copy" ? assignment.fromField === "" : assignment.value === "") {
        hints.push(`${assignment.field} has no ${assignment.mode === "copy" ? "field to copy from" : "value"}.`);
      }
    });
  }
  return hints;
}

/** The indexes of the actions that are not finished yet. */
export function unfinishedActions(draft: WorkflowDraft, catalogue: Catalogue): number[] {
  const open: number[] = [];
  draft.actions.forEach((action, at) => {
    const type = catalogue.actionTypes.find((one) => one.type === action.type);
    if (type === undefined || actionHints(action, type).length > 0 || action.name.trim() === "") {
      open.push(at);
    }
  });
  return open;
}

/** What is not finished in the trigger: a property the kind uses and the draft leaves empty. */
export function triggerHints(trigger: TriggerDraft, kind: CatalogueTriggerKind | undefined): string[] {
  if (kind === undefined) {
    return ["Choose when the workflow starts."];
  }
  const hints: string[] = [];
  const uses = new Set(kind.properties);
  if (uses.has("fields") && trigger.fields.length === 0) {
    hints.push("Choose at least one field.");
  }
  if (uses.has("field") && trigger.field === "") {
    hints.push("Choose the date field.");
  }
  if (uses.has("days") && trigger.days.trim() === "") {
    hints.push("Say how many days.");
  }
  if (uses.has("at") && trigger.at === "") {
    hints.push("Say when.");
  }
  return hints;
}

// ---- the fields a form may offer ----

/**
 * The fields of an object a workflow may set: not a system field, not retired, and of a type the platform does not work
 * out. Which types those are is the API's list of field types, not the page's.
 */
export function settableFields(fields: readonly FieldView[], types: readonly FieldTypeView[]): FieldView[] {
  const calculated = new Set(types.filter((type) => type.calculated).map((type) => type.type));
  return fields.filter((field) => field.kind !== "SYSTEM" && !field.retired && !calculated.has(field.type));
}

/** A code of the API in words a person reads: SOME_CODE becomes "Some code". */
export function humanize(code: string): string {
  const words = code.toLowerCase().replace(/_/g, " ").trim();
  return words === "" ? code : words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * The workflow list text for a trigger: the summary the catalogue gives for its kind, so the page names no kind of its own.
 */
export function triggerText(trigger: TriggerSpec, catalogue: Catalogue): string {
  return catalogue.triggerKinds.find((kind) => kind.kind === trigger.kind)?.summary ?? humanize(trigger.kind);
}

// ---- the two phases of a workflow (Sprint 25, ADR-0160) ----

/** When a workflow's actions run: after the record change is committed, or inside the save, before it is stored. */
export type Phase = "AFTER_SAVE" | "BEFORE_SAVE";

/** The phase of a kind of trigger, as the catalogue says it. */
export function phaseOf(kind: CatalogueTriggerKind | undefined): Phase {
  return kind?.phase === "BEFORE_SAVE" ? "BEFORE_SAVE" : "AFTER_SAVE";
}

/** The action types the catalogue offers in a phase. A before-save workflow takes only the types of its own phase. */
export function actionTypesFor(catalogue: Catalogue, phase: Phase): CatalogueActionType[] {
  return catalogue.actionTypes.filter((type) => (type.phase === "BEFORE_SAVE" ? "BEFORE_SAVE" : "AFTER_SAVE") === phase);
}

/**
 * The draft with another trigger. When the trigger moves to the other phase the actions of the old phase cannot stay (the
 * API refuses a before-save trigger with an after-save action and the other way round), so they are dropped, and the
 * caller says so.
 */
export function withTrigger(draft: WorkflowDraft, trigger: TriggerDraft, catalogue: Catalogue): WorkflowDraft {
  const before = phaseOf(catalogue.triggerKinds.find((one) => one.kind === draft.trigger.kind));
  const after = phaseOf(catalogue.triggerKinds.find((one) => one.kind === trigger.kind));
  if (before === after) {
    return { ...draft, trigger };
  }
  const allowed = new Set(actionTypesFor(catalogue, after).map((type) => type.type));
  return { ...draft, trigger, actions: draft.actions.filter((action) => allowed.has(action.type)) };
}

/** How many actions the move to another trigger would drop, so the builder can tell the person before it happens. */
export function actionsLostByTrigger(draft: WorkflowDraft, kind: string, catalogue: Catalogue): number {
  const next = phaseOf(catalogue.triggerKinds.find((one) => one.kind === kind));
  const allowed = new Set(actionTypesFor(catalogue, next).map((type) => type.type));
  return draft.actions.filter((action) => !allowed.has(action.type)).length;
}

/** What is not finished because of the phase: a before-save automation has exactly one action. A hint, not a rule. */
export function phaseHints(draft: WorkflowDraft, catalogue: Catalogue): string[] {
  const kind = catalogue.triggerKinds.find((one) => one.kind === draft.trigger.kind);
  if (phaseOf(kind) === "BEFORE_SAVE" && draft.actions.length !== 1) {
    return ["A before-save automation has exactly one action; it sets fields of the record being saved."];
  }
  return [];
}
