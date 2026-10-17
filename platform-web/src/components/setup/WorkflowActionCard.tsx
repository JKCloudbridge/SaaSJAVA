"use client";

import { DropZone } from "@/components/builder/DropZone";
import { MoveButtons } from "@/components/builder/MoveButtons";
import type { components } from "@/lib/api/generated/schema";
import { startDrag, type DragPayload } from "@/lib/builder/dragPayload";
import {
  actionHints,
  addAssignment,
  assignedObjectOf,
  changeAction,
  changeAssignment,
  changeSetting,
  humanize,
  removeAssignment,
  type ActionDraft,
  type CatalogueActionType,
} from "@/lib/builder/workflowModel";

type FieldView = components["schemas"]["FieldView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

export const ACTION_GROUP = "workflow-actions";

/**
 * One action of the workflow on the canvas: its name, its settings and the fields it sets, all drawn from what the API
 * says the type takes. Changing it only changes the draft; whether the workflow is acceptable is the API's answer when it is
 * saved or tried, and the words of a problem are shown under the action they are about.
 */
export function WorkflowActionCard({
  index,
  count,
  action,
  type,
  workflowObject,
  objects,
  fieldsOf,
  settableOf,
  problems,
  attemptsLimit,
  onChange,
  onDrop,
}: {
  index: number;
  count: number;
  action: ActionDraft;
  type: CatalogueActionType | undefined;
  workflowObject: string;
  objects: readonly ObjectSummary[];
  /** Every field of an object (the workflow's, to copy from), or nothing while it is being read. */
  fieldsOf: (objectApiName: string) => readonly FieldView[];
  /** The fields of an object that may be set. */
  settableOf: (objectApiName: string) => readonly FieldView[];
  problems: readonly string[];
  attemptsLimit: number;
  onChange: (change: (actions: readonly ActionDraft[]) => ActionDraft[]) => void;
  onDrop: (payload: DragPayload, before: number) => void;
}) {
  const hints = actionHints(action, type);
  const target = assignedObjectOf(action, type, workflowObject);
  const targetFields = target ? settableOf(target) : [];
  const sourceFields = fieldsOf(workflowObject).filter((field) => !field.retired);
  const prefix = `action-${action.id}`;
  const open = hints.length > 0;

  return (
    <DropZone
      className={["item-chip", "item-chip-wide", "item-chip-stack", open ? "is-open" : ""].filter(Boolean).join(" ")}
      data-testid="workflow-action"
      onDropPayload={(payload: DragPayload) => onDrop(payload, index)}
    >
      <div className="entry-fields">
        <span
          className="drag-handle"
          draggable
          title="Drag to move the action"
          aria-hidden="true"
          onDragStart={(event) => startDrag(event, { origin: "canvas", group: ACTION_GROUP, index })}
        >
          ⠿
        </span>
        <span className="badge" title={type?.summary}>
          {humanize(action.type)}
        </span>
        <label className="visually-hidden" htmlFor={`${prefix}-name`}>
          Name of the action
        </label>
        <input
          id={`${prefix}-name`}
          value={action.name}
          maxLength={60}
          onChange={(event) => onChange((actions) => changeAction(actions, index, { name: event.target.value }))}
        />
        <label htmlFor={`${prefix}-attempts`} className="hint">
          Attempts
        </label>
        <input
          id={`${prefix}-attempts`}
          type="number"
          min={1}
          max={attemptsLimit}
          inputMode="numeric"
          placeholder={String(attemptsLimit)}
          title={`How many times the action is tried. Left empty it is ${attemptsLimit}, the most the platform allows.`}
          value={action.maxAttempts}
          onChange={(event) => onChange((actions) => changeAction(actions, index, { maxAttempts: event.target.value }))}
        />
        <MoveButtons
          name={action.name || humanize(action.type)}
          onUp={index > 0 ? () => onChange((actions) => moveBy(actions, index, -1)) : undefined}
          onDown={index < count - 1 ? () => onChange((actions) => moveBy(actions, index, 1)) : undefined}
          onRemove={() => onChange((actions) => actions.filter((_, at) => at !== index))}
        />
      </div>

      {type ? <p className="hint">{type.summary}</p> : <p className="form-message">This release does not carry this action out.</p>}

      {(type?.settings ?? []).map((setting) => {
        const id = `${prefix}-setting-${setting.key}`;
        const value = action.settings[setting.key] ?? "";
        const set = (text: string) => onChange((actions) => changeSetting(actions, index, setting.key, text));
        const choices =
          setting.refersTo === "OBJECT"
            ? objects.map((one) => ({ value: one.apiName, label: one.label }))
            : setting.refersTo === "FIELD"
              ? sourceFields.map((field) => ({ value: field.apiName, label: field.label }))
              : setting.refersTo === "ASSIGNED_FIELD"
                ? fieldsOf(target ?? "").filter((field) => !field.retired).map((field) => ({
                    value: field.apiName,
                    label: field.label,
                  }))
                : setting.allowed.map((one) => ({ value: one, label: humanize(one) }));
        return (
          <div className="field" key={setting.key}>
            <label htmlFor={id}>
              {humanize(setting.key.replace(/([A-Z])/g, "_$1"))}
              {setting.required ? "" : " (optional)"}
            </label>
            {choices.length > 0 ? (
              <select id={id} value={value} onChange={(event) => set(event.target.value)} aria-describedby={`${id}-hint`}>
                <option value="">{setting.required ? "Choose…" : "None"}</option>
                {choices.map((one) => (
                  <option key={one.value} value={one.value}>
                    {one.label}
                  </option>
                ))}
              </select>
            ) : (
              <input id={id} value={value} onChange={(event) => set(event.target.value)} aria-describedby={`${id}-hint`} />
            )}
            <p className="hint" id={`${id}-hint`}>
              {setting.help}
            </p>
          </div>
        );
      })}

      {type && type.assignments !== "NONE" ? (
        <fieldset className="field" data-testid="assignments">
          <legend>Fields it sets</legend>
          {target === undefined ? (
            <p className="hint">Choose the object first; its fields are offered here.</p>
          ) : (
            <>
              {action.assignments.map((assignment, at) => {
                const row = `${prefix}-assignment-${assignment.id}`;
                const chosen = targetFields.find((field) => field.apiName === assignment.field);
                const choices = valuesOf(chosen);
                return (
                  <div key={assignment.id} className="filter-row" role="group" aria-label={`Field ${at + 1}`}>
                    <label className="visually-hidden" htmlFor={`${row}-field`}>
                      Field to set
                    </label>
                    <select
                      id={`${row}-field`}
                      value={assignment.field}
                      onChange={(event) =>
                        onChange((actions) => changeAssignment(actions, index, at, { field: event.target.value }))
                      }
                    >
                      <option value="">Choose a field</option>
                      {targetFields.map((field) => (
                        <option key={field.apiName} value={field.apiName}>
                          {field.label}
                        </option>
                      ))}
                    </select>
                    <label className="visually-hidden" htmlFor={`${row}-mode`}>
                      Where the value comes from
                    </label>
                    <select
                      id={`${row}-mode`}
                      value={assignment.mode}
                      onChange={(event) =>
                        onChange((actions) =>
                          changeAssignment(actions, index, at, { mode: event.target.value === "copy" ? "copy" : "value" }),
                        )
                      }
                    >
                      <option value="value">A value written here</option>
                      <option value="copy">Copied from a field of the record</option>
                    </select>
                    {assignment.mode === "copy" ? (
                      <>
                        <label className="visually-hidden" htmlFor={`${row}-from`}>
                          Field to copy from
                        </label>
                        <select
                          id={`${row}-from`}
                          value={assignment.fromField}
                          onChange={(event) =>
                            onChange((actions) => changeAssignment(actions, index, at, { fromField: event.target.value }))
                          }
                        >
                          <option value="">Choose a field</option>
                          {sourceFields.map((field) => (
                            <option key={field.apiName} value={field.apiName}>
                              {field.label}
                            </option>
                          ))}
                        </select>
                      </>
                    ) : (
                      <>
                        <label className="visually-hidden" htmlFor={`${row}-value`}>
                          Value
                        </label>
                        <input
                          id={`${row}-value`}
                          value={assignment.value}
                          list={choices.length > 0 ? `${row}-choices` : undefined}
                          placeholder="Value, written as a default value is"
                          onChange={(event) =>
                            onChange((actions) => changeAssignment(actions, index, at, { value: event.target.value }))
                          }
                        />
                        {choices.length > 0 ? (
                          <datalist id={`${row}-choices`}>
                            {choices.map((one) => (
                              <option key={one} value={one} />
                            ))}
                          </datalist>
                        ) : null}
                      </>
                    )}
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => onChange((actions) => removeAssignment(actions, index, at))}
                    >
                      Remove
                    </button>
                  </div>
                );
              })}
              <div>
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={() => onChange((actions) => addAssignment(actions, index))}
                >
                  Add a field
                </button>
              </div>
            </>
          )}
        </fieldset>
      ) : null}

      {hints.length > 0 ? (
        <ul className="hint" data-testid="action-hints">
          {hints.map((hint) => (
            <li key={hint}>{hint}</li>
          ))}
        </ul>
      ) : null}
      {problems.length > 0 ? (
        <ul className="form-message" role="alert" data-testid="action-problems">
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      ) : null}
    </DropZone>
  );
}

function moveBy(actions: readonly ActionDraft[], index: number, delta: number): ActionDraft[] {
  const next = [...actions];
  const target = index + delta;
  if (target < 0 || target >= next.length) {
    return next;
  }
  const [taken] = next.splice(index, 1);
  next.splice(target, 0, taken as ActionDraft);
  return next;
}

/** The values a picklist field lists, for the suggestions of a literal; none for any other field. */
function valuesOf(field: FieldView | undefined): string[] {
  const values = (field?.settings as { values?: { value: string; active?: boolean }[] } | undefined)?.values;
  return Array.isArray(values) ? values.filter((one) => one.active !== false).map((one) => one.value) : [];
}
