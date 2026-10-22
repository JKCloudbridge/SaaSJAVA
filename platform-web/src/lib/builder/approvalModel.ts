import type { components } from "@/lib/api/generated/schema";
import { uid } from "./ids";
import { insertAt, move, removeAt, updateAt } from "./reorder";

type ProcessView = components["schemas"]["ApprovalProcessView"];
type StepSpec = components["schemas"]["ApprovalStepSpec"];
type ApproverSpec = components["schemas"]["ApprovalApproverSpec"];
type FieldSpec = components["schemas"]["ApprovalFieldSpec"];
type FieldView = components["schemas"]["FieldView"];

export type Catalogue = components["schemas"]["ApprovalCatalogueView"];
export type ApproverKind = components["schemas"]["ApproverKind"];
export type Choice = components["schemas"]["Choice"];

/*
 * The model of the approval builder (Sprint 24, ADR-0132, ADR-0145, ADR-0146). Pure: no React, no network. A draft is what
 * the person is editing; the API's shape is made from it only when they save. The model holds no list of approver kinds,
 * completion rules, outcomes, states or limits: every one of them is read from the catalogue the API gives. What it says
 * about a draft is a hint (something is not finished yet); whether the process is acceptable is the API's answer, in its
 * words with the place that is wrong.
 */

/** One rule that names who is asked, as the builder holds it. `id` identifies it in the browser only. */
export interface ApproverDraft {
  id: string;
  kind: string;
  /** What the kind's one setting holds, as typed or chosen; empty for a kind that takes none. */
  value: string;
}

/** One step, as the builder holds it. */
export interface StepDraft {
  id: string;
  name: string;
  label: string;
  completion: string;
  approvers: ApproverDraft[];
  /** Skip the step when nobody can be asked to decide it, after the fallback approver was tried (FA4A, ADR-0187). */
  skipWhenNobody: boolean;
}

/** One field an outcome sets, to a literal written as text. */
export interface OutcomeFieldDraft {
  id: string;
  field: string;
  value: string;
}

/** The outcomes, by the code the catalogue gives (APPROVED, ...): the lists of fields each sets. */
export type OutcomesDraft = Record<string, OutcomeFieldDraft[]>;

export interface ApprovalDraft {
  name: string;
  label: string;
  description: string;
  active: boolean;
  entryCriteria: string;
  allowSelfApproval: boolean;
  steps: StepDraft[];
  outcomes: OutcomesDraft;
  /** The fallback approver: empty kind means none. */
  fallback: { kind: string; value: string };
  /** Hours an active step may wait before the people asked are reminded, as typed; empty means never (Sprint 25). */
  remindAfterHours: string;
  /** Hours an active step may wait before the fallback approver is asked as well, as typed; empty means never. */
  escalateAfterHours: string;
}

// ---- making a draft ----

/** A new process: no name yet, switched on, no steps. Every outcome the catalogue lists starts empty. */
export function emptyDraft(catalogue: Catalogue): ApprovalDraft {
  return {
    name: "",
    label: "",
    description: "",
    active: true,
    entryCriteria: "",
    allowSelfApproval: false,
    steps: [],
    outcomes: emptyOutcomes(catalogue),
    fallback: { kind: "", value: "" },
    remindAfterHours: "",
    escalateAfterHours: "",
  };
}

function emptyOutcomes(catalogue: Catalogue): OutcomesDraft {
  const outcomes: OutcomesDraft = {};
  for (const one of catalogue.outcomes) {
    outcomes[one.code] = [];
  }
  return outcomes;
}

export function approverFromSpec(spec: ApproverSpec): ApproverDraft {
  return { id: uid(), kind: spec.kind, value: spec.value ?? "" };
}

export function stepFromSpec(spec: StepSpec): StepDraft {
  return {
    id: uid(),
    name: spec.name,
    label: spec.label,
    completion: spec.completion,
    approvers: spec.approvers.map(approverFromSpec),
    // A process saved before the flag existed has none, which means "never skipped".
    skipWhenNobody: spec.skipWhenNobody === true,
  };
}

function outcomeFieldsFrom(specs: readonly FieldSpec[] | undefined): OutcomeFieldDraft[] {
  return (specs ?? []).map((spec) => ({ id: uid(), field: spec.field, value: spec.value }));
}

/**
 * The draft of a process the API gave. The outcomes are read by the codes the catalogue lists, which is how the API names
 * them in its answer (the answer carries one list per outcome, under the lower case code).
 */
export function draftFromView(view: ProcessView, catalogue: Catalogue): ApprovalDraft {
  const outcomes = emptyOutcomes(catalogue);
  const given = view.outcomes as unknown as Record<string, readonly FieldSpec[] | undefined>;
  for (const one of catalogue.outcomes) {
    outcomes[one.code] = outcomeFieldsFrom(given[one.code.toLowerCase()]);
  }
  return {
    name: view.apiName,
    label: view.label,
    description: view.description,
    active: view.active,
    entryCriteria: view.entryCriteria ?? "",
    allowSelfApproval: view.allowSelfApproval,
    steps: view.steps.map(stepFromSpec),
    outcomes,
    fallback: view.fallback ? { kind: view.fallback.kind, value: view.fallback.value ?? "" } : { kind: "", value: "" },
    remindAfterHours: view.remindAfterHours === undefined || view.remindAfterHours === null ? "" : String(view.remindAfterHours),
    escalateAfterHours:
      view.escalateAfterHours === undefined || view.escalateAfterHours === null ? "" : String(view.escalateAfterHours),
  };
}

// ---- making the request ----

/** The approver to send: the kind and, when the kind names something, its value; never a value for a kind that takes none. */
export function approverSpec(approver: ApproverDraft, kind: ApproverKind | undefined): ApproverSpec {
  const takesValue = kind !== undefined && kind.valueRefersTo !== "NOTHING";
  const value = approver.value.trim();
  return takesValue && value !== "" ? { kind: approver.kind, value } : { kind: approver.kind };
}

export function stepSpec(step: StepDraft, catalogue: Catalogue): StepSpec {
  return {
    name: step.name.trim(),
    label: step.label.trim(),
    completion: step.completion,
    approvers: step.approvers.map((approver) =>
      approverSpec(
        approver,
        catalogue.approverKinds.find((one) => one.kind === approver.kind),
      ),
    ),
    skipWhenNobody: step.skipWhenNobody,
  };
}

/** The outcomes to send: one list per code the catalogue gives, under the lower case name the API uses. */
export function outcomesSpec(draft: ApprovalDraft, catalogue: Catalogue): Record<string, FieldSpec[]> {
  const spec: Record<string, FieldSpec[]> = {};
  for (const one of catalogue.outcomes) {
    spec[one.code.toLowerCase()] = (draft.outcomes[one.code] ?? []).map(
      (field): FieldSpec => ({ field: field.field, value: field.value }),
    );
  }
  return spec;
}

/** The parts of the request every save sends, whether it makes the process or changes it. */
export function definitionOf(draft: ApprovalDraft, catalogue: Catalogue) {
  const criteria = draft.entryCriteria.trim();
  const fallbackKind = catalogue.approverKinds.find((one) => one.kind === draft.fallback.kind);
  return {
    ...(criteria === "" ? {} : { entryCriteria: criteria }),
    allowSelfApproval: draft.allowSelfApproval,
    steps: draft.steps.map((step) => stepSpec(step, catalogue)),
    outcomes: outcomesSpec(draft, catalogue) as components["schemas"]["ApprovalOutcomesSpec"],
    ...(draft.fallback.kind === ""
      ? {}
      : { fallback: approverSpec({ id: "", kind: draft.fallback.kind, value: draft.fallback.value }, fallbackKind) }),
    ...(draft.remindAfterHours.trim() === "" ? {} : { remindAfterHours: Number(draft.remindAfterHours) }),
    ...(draft.escalateAfterHours.trim() === "" ? {} : { escalateAfterHours: Number(draft.escalateAfterHours) }),
  };
}

/** A string that changes when the draft does, for the "unsaved changes" badge: the draft is compared with what was read. */
export function snapshotOf(draft: ApprovalDraft): string {
  return JSON.stringify({
    ...draft,
    steps: draft.steps.map((step) => ({
      name: step.name,
      label: step.label,
      completion: step.completion,
      skipWhenNobody: step.skipWhenNobody,
      approvers: step.approvers.map((approver) => ({ kind: approver.kind, value: approver.value })),
    })),
    outcomes: Object.fromEntries(
      Object.entries(draft.outcomes).map(([code, fields]) => [
        code,
        fields.map((field) => ({ field: field.field, value: field.value })),
      ]),
    ),
  });
}

// ---- the steps ----

/** A name for a new step that no other step has: Step1, Step2, ... */
export function freshStepName(steps: readonly StepDraft[]): string {
  const taken = new Set(steps.map((step) => step.name.toLowerCase()));
  for (let number = steps.length + 1; ; number += 1) {
    const name = `Step${number}`;
    if (!taken.has(name.toLowerCase())) {
      return name;
    }
  }
}

/** A new step: the first completion rule the API lists, and no approver yet. */
export function newStep(steps: readonly StepDraft[], catalogue: Catalogue): StepDraft {
  return {
    id: uid(),
    name: freshStepName(steps),
    label: "",
    completion: catalogue.completions[0]?.code ?? "",
    approvers: [],
    skipWhenNobody: false,
  };
}

export function insertStep(steps: readonly StepDraft[], step: StepDraft, before?: number): StepDraft[] {
  return insertAt(steps, step, before);
}

/** Moves the step at `from` before the step that was at `before` (or to the end), counting the list as the drop saw it. */
export function moveStepBefore(steps: readonly StepDraft[], from: number, before?: number): StepDraft[] {
  const at = before === undefined ? steps.length : before;
  const to = from < at ? at - 1 : at;
  return move(steps, from, Math.min(Math.max(to, 0), steps.length - 1));
}

export function nudgeStep(steps: readonly StepDraft[], index: number, delta: number): StepDraft[] {
  return move(steps, index, index + delta);
}

export function removeStep(steps: readonly StepDraft[], index: number): StepDraft[] {
  return removeAt(steps, index);
}

export function changeStep(
  steps: readonly StepDraft[],
  index: number,
  change: Partial<Omit<StepDraft, "id" | "approvers">>,
): StepDraft[] {
  return updateAt(steps, index, (step) => ({ ...step, ...change }));
}

// ---- the approver rules of a step ----

export function addApprover(steps: readonly StepDraft[], index: number, kind: string): StepDraft[] {
  return updateAt(steps, index, (step) => ({
    ...step,
    approvers: [...step.approvers, { id: uid(), kind, value: "" }],
  }));
}

export function changeApprover(
  steps: readonly StepDraft[],
  index: number,
  approverIndex: number,
  change: Partial<Omit<ApproverDraft, "id">>,
): StepDraft[] {
  return updateAt(steps, index, (step) => ({
    ...step,
    approvers: updateAt(step.approvers, approverIndex, (approver) => ({ ...approver, ...change })),
  }));
}

export function removeApprover(steps: readonly StepDraft[], index: number, approverIndex: number): StepDraft[] {
  return updateAt(steps, index, (step) => ({ ...step, approvers: removeAt(step.approvers, approverIndex) }));
}

// ---- the outcomes ----

export function addOutcomeField(outcomes: OutcomesDraft, code: string): OutcomesDraft {
  return { ...outcomes, [code]: [...(outcomes[code] ?? []), { id: uid(), field: "", value: "" }] };
}

export function changeOutcomeField(
  outcomes: OutcomesDraft,
  code: string,
  index: number,
  change: Partial<Omit<OutcomeFieldDraft, "id">>,
): OutcomesDraft {
  return { ...outcomes, [code]: updateAt(outcomes[code] ?? [], index, (field) => ({ ...field, ...change })) };
}

export function removeOutcomeField(outcomes: OutcomesDraft, code: string, index: number): OutcomesDraft {
  return { ...outcomes, [code]: removeAt(outcomes[code] ?? [], index) };
}

// ---- hints ----

/** What the value of a rule names, as the catalogue says it: {@code NOTHING} for a kind that takes no value. */
export function valueNames(approver: ApproverDraft, catalogue: Catalogue): string {
  return catalogue.approverKinds.find((one) => one.kind === approver.kind)?.valueRefersTo ?? "NOTHING";
}

/** What is not finished in one step: a hint while building. The API checks the saved process and says what is wrong. */
export function stepHints(step: StepDraft, catalogue: Catalogue): string[] {
  const hints: string[] = [];
  if (step.label.trim() === "") {
    hints.push("Give the step a label.");
  }
  if (step.approvers.length === 0) {
    hints.push("Say who is asked.");
  }
  step.approvers.forEach((approver, at) => {
    if (valueNames(approver, catalogue) !== "NOTHING" && approver.value.trim() === "") {
      hints.push(`Rule ${at + 1} needs ${approver.kind ? "a choice" : "a kind"}.`);
    }
  });
  return hints;
}

/** The indexes of the steps that are not finished yet. */
export function unfinishedSteps(draft: ApprovalDraft, catalogue: Catalogue): number[] {
  const open: number[] = [];
  draft.steps.forEach((step, at) => {
    if (step.name.trim() === "" || stepHints(step, catalogue).length > 0) {
      open.push(at);
    }
  });
  return open;
}

/** The outcome fields that are not finished: a field without a value or a value without a field. */
export function unfinishedOutcomes(draft: ApprovalDraft): number {
  let open = 0;
  for (const fields of Object.values(draft.outcomes)) {
    for (const field of fields) {
      if (field.field === "" || field.value === "") {
        open += 1;
      }
    }
  }
  return open;
}

// ---- the fields a form may offer ----

/** The name the platform gives to the object of its users (a lookup to it is a user field). */
const USER_OBJECT = "User";

/**
 * The user fields of an object: a lookup to the platform's users. This only narrows the choice; the API checks the saved
 * process and says when a field it names is not a link to a user.
 */
export function userFields(fields: readonly FieldView[]): FieldView[] {
  return fields.filter(
    (field) => !field.retired && field.type === "LOOKUP" && field.settings?.targetObject === USER_OBJECT,
  );
}

/** The fields an outcome may set: not a system field, not retired, of a type the platform does not work out. */
export function settableFields(fields: readonly FieldView[], calculated: ReadonlySet<string>): FieldView[] {
  return fields.filter((field) => field.kind !== "SYSTEM" && !field.retired && !calculated.has(field.type));
}

/** A code of the API in words a person reads: SOME_CODE becomes "Some code". */
export function humanize(code: string): string {
  const words = code.toLowerCase().replace(/_/g, " ").trim();
  return words === "" ? code : words.charAt(0).toUpperCase() + words.slice(1);
}
