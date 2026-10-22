"use client";

import { useState } from "react";
import { PersonPicker } from "@/components/approvals/PersonPicker";
import { DropZone } from "@/components/builder/DropZone";
import { MoveButtons } from "@/components/builder/MoveButtons";
import type { components } from "@/lib/api/generated/schema";
import {
  addApprover,
  changeApprover,
  changeStep,
  humanize,
  removeApprover,
  stepHints,
  valueNames,
  type Catalogue,
  type StepDraft,
} from "@/lib/builder/approvalModel";
import { startDrag, type DragPayload } from "@/lib/builder/dragPayload";

type FieldView = components["schemas"]["FieldView"];
type Choices = components["schemas"]["ApprovalChoicesView"];

export const STEP_GROUP = "approval-steps";

/**
 * One step of the approval process on the canvas: its name, label and completion rule, and the rules that say who is asked,
 * all drawn from what the API says exists. Changing it only changes the draft; whether the process is acceptable is the
 * API's answer when it is saved or tried, and the words of a problem are shown under the step they are about. A rule's
 * value is chosen from what the API lists (profiles, roles, groups, the user fields of the object) or found by name
 * (people); the page names no kind of rule and no completion rule of its own.
 */
export function ApprovalStepCard({
  index,
  count,
  step,
  catalogue,
  choices,
  userFields,
  names,
  problems,
  onChange,
  onName,
  onDrop,
}: {
  index: number;
  count: number;
  step: StepDraft;
  catalogue: Catalogue;
  choices: Choices | undefined;
  userFields: readonly FieldView[];
  /** The names of the people chosen so far, by identifier (a saved process shows the identifier until it is known). */
  names: Readonly<Record<string, string>>;
  problems: readonly string[];
  onChange: (change: (steps: readonly StepDraft[]) => StepDraft[]) => void;
  onName: (identifier: string, name: string) => void;
  onDrop: (payload: DragPayload, before: number) => void;
}) {
  const hints = stepHints(step, catalogue);
  const prefix = `step-${step.id}`;
  const [kind, setKind] = useState(catalogue.approverKinds[0]?.kind ?? "");

  return (
    <DropZone
      className={["item-chip", "item-chip-wide", "item-chip-stack", hints.length > 0 ? "is-open" : ""]
        .filter(Boolean)
        .join(" ")}
      data-testid="approval-step"
      onDropPayload={(payload: DragPayload) => onDrop(payload, index)}
    >
      <div className="entry-fields">
        <span
          className="drag-handle"
          draggable
          title="Drag to move the step"
          aria-hidden="true"
          onDragStart={(event) => startDrag(event, { origin: "canvas", group: STEP_GROUP, index })}
        >
          ⠿
        </span>
        <span className="badge">Step {index + 1}</span>
        <label className="visually-hidden" htmlFor={`${prefix}-label`}>
          Label of the step
        </label>
        <input
          id={`${prefix}-label`}
          value={step.label}
          maxLength={80}
          placeholder="What this step is, for example Manager"
          onChange={(event) => onChange((steps) => changeStep(steps, index, { label: event.target.value }))}
        />
        <label className="visually-hidden" htmlFor={`${prefix}-name`}>
          Name of the step
        </label>
        <input
          id={`${prefix}-name`}
          value={step.name}
          maxLength={60}
          title="The name of the step inside the process"
          onChange={(event) => onChange((steps) => changeStep(steps, index, { name: event.target.value }))}
        />
        <MoveButtons
          name={step.label || step.name || `step ${index + 1}`}
          onUp={index > 0 ? () => onChange((steps) => moveBy(steps, index, -1)) : undefined}
          onDown={index < count - 1 ? () => onChange((steps) => moveBy(steps, index, 1)) : undefined}
          onRemove={() => onChange((steps) => steps.filter((_, at) => at !== index))}
        />
      </div>

      <div className="field">
        <label htmlFor={`${prefix}-completion`}>Who must approve</label>
        <select
          id={`${prefix}-completion`}
          value={step.completion}
          onChange={(event) => onChange((steps) => changeStep(steps, index, { completion: event.target.value }))}
        >
          {catalogue.completions.map((one) => (
            <option key={one.code} value={one.code}>
              {one.summary}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor={`${prefix}-skip`}>
          <input
            id={`${prefix}-skip`}
            type="checkbox"
            checked={step.skipWhenNobody}
            onChange={(event) => onChange((steps) => changeStep(steps, index, { skipWhenNobody: event.target.checked }))}
          />{" "}
          Skip this step when nobody can decide it
        </label>
        <p className="hint" id={`${prefix}-skip-hint`}>
          If the rules name nobody who can decide, the fallback approver is asked first. Only when nobody is left is the
          step skipped, and the history says so. A step that is not skipped makes a first step refuse the submit and a
          later step wait for an administrator.
        </p>
      </div>

      <fieldset className="field" data-testid="approvers">
        <legend>Who is asked</legend>
        {step.approvers.length === 0 ? (
          <p className="hint">Nobody yet. Add a rule below, or drag one in from the palette.</p>
        ) : null}
        {step.approvers.map((approver, at) => {
          const row = `${prefix}-approver-${approver.id}`;
          const info = catalogue.approverKinds.find((one) => one.kind === approver.kind);
          const names_ = valueNames(approver, catalogue);
          const set = (value: string) => onChange((steps) => changeApprover(steps, index, at, { value }));
          return (
            <div key={approver.id} className="filter-row" role="group" aria-label={`Rule ${at + 1}`}>
              <span className="badge" title={info?.summary}>
                {humanize(approver.kind)}
              </span>
              {names_ === "USER" ? (
                <PersonPicker
                  id={`${row}-person`}
                  label="Person"
                  value={approver.value}
                  name={names[approver.value]}
                  onChange={(identifier, name) => {
                    onName(identifier, name);
                    set(identifier);
                  }}
                />
              ) : null}
              {names_ === "PROFILE" || names_ === "ROLE" || names_ === "GROUP" ? (
                <ChoiceSelect
                  id={`${row}-value`}
                  label={humanize(names_)}
                  value={approver.value}
                  options={namedOf(choices, names_)}
                  onChange={set}
                />
              ) : null}
              {names_ === "USER_FIELD" ? (
                <ChoiceSelect
                  id={`${row}-value`}
                  label="User field"
                  value={approver.value}
                  options={userFields.map((field) => ({ id: field.apiName, name: field.label }))}
                  onChange={set}
                />
              ) : null}
              {info ? <span className="hint">{info.summary}</span> : null}
              <button
                type="button"
                className="link-button"
                onClick={() => onChange((steps) => removeApprover(steps, index, at))}
              >
                Remove
              </button>
            </div>
          );
        })}
        <div className="filter-row">
          <label htmlFor={`${prefix}-add-kind`} className="visually-hidden">
            Kind of rule to add
          </label>
          <select id={`${prefix}-add-kind`} value={kind} onChange={(event) => setKind(event.target.value)}>
            {catalogue.approverKinds.map((one) => (
              <option key={one.kind} value={one.kind}>
                {humanize(one.kind)}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="button button-secondary"
            disabled={kind === ""}
            onClick={() => onChange((steps) => addApprover(steps, index, kind))}
          >
            Add a rule
          </button>
        </div>
      </fieldset>

      {hints.length > 0 ? (
        <ul className="hint" data-testid="step-hints">
          {hints.map((hint) => (
            <li key={hint}>{hint}</li>
          ))}
        </ul>
      ) : null}
      {problems.length > 0 ? (
        <ul className="form-message" role="alert" data-testid="step-problems">
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      ) : null}
    </DropZone>
  );
}

function ChoiceSelect({
  id,
  label,
  value,
  options,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  options: readonly { id: string; name: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Choose…</option>
        {options.map((one) => (
          <option key={one.id} value={one.id}>
            {one.name}
          </option>
        ))}
      </select>
    </div>
  );
}

/** The profiles, roles or groups a rule can name, by what its kind names (the API says which). */
function namedOf(choices: Choices | undefined, names: string): readonly { id: string; name: string }[] {
  if (!choices) {
    return [];
  }
  return names === "PROFILE" ? choices.profiles : names === "ROLE" ? choices.roles : choices.groups;
}

function moveBy(steps: readonly StepDraft[], index: number, delta: number): StepDraft[] {
  const next = [...steps];
  const target = index + delta;
  if (target < 0 || target >= next.length) {
    return next;
  }
  const [taken] = next.splice(index, 1);
  next.splice(target, 0, taken as StepDraft);
  return next;
}
