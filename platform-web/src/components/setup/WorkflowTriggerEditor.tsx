"use client";

import type { components } from "@/lib/api/generated/schema";
import { humanize, type Catalogue, type TriggerDraft } from "@/lib/builder/workflowModel";
import { WorkflowSelectionEditor } from "./WorkflowSelectionEditor";

type FieldView = components["schemas"]["FieldView"];

/** The days of the week the API takes by name: a calendar, not a setting of the platform. */
const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

/**
 * When a workflow starts: the kinds of trigger the API offers (each with its own words), and for the chosen kind the
 * properties the API says it uses. The page names no kind and no property of its own; what is not acceptable is the API's
 * answer when the workflow is saved.
 */
export function WorkflowTriggerEditor({
  catalogue,
  objectApiName,
  trigger,
  fields,
  onChange,
}: {
  catalogue: Catalogue;
  objectApiName: string;
  trigger: TriggerDraft;
  fields: readonly FieldView[];
  onChange: (trigger: TriggerDraft) => void;
}) {
  const kind = catalogue.triggerKinds.find((one) => one.kind === trigger.kind);
  const uses = new Set(kind?.properties ?? []);
  const change = (part: Partial<TriggerDraft>) => onChange({ ...trigger, ...part });
  const usable = fields.filter((field) => !field.retired);

  return (
    <div className="stack" data-testid="trigger-editor">
      <fieldset className="field">
        <legend>Starts when</legend>
        <div className="palette-grid" role="radiogroup" aria-label="When the workflow starts">
          {catalogue.triggerKinds.map((one) => (
            <button
              key={one.kind}
              type="button"
              role="radio"
              aria-checked={one.kind === trigger.kind}
              className={["palette-chip", one.kind === trigger.kind ? "palette-chip-new" : ""].filter(Boolean).join(" ")}
              title={one.summary}
              onClick={() => change({ kind: one.kind })}
            >
              {humanize(one.kind)}
            </button>
          ))}
        </div>
        {kind ? <p className="hint">{kind.summary}</p> : null}
      </fieldset>

      {uses.has("fields") ? (
        <fieldset className="field">
          <legend>Any of these fields changes</legend>
          <div className="stack">
            {usable.map((field) => (
              <label key={field.apiName}>
                <input
                  type="checkbox"
                  checked={trigger.fields.includes(field.apiName)}
                  onChange={(event) =>
                    change({
                      fields: event.target.checked
                        ? [...trigger.fields, field.apiName]
                        : trigger.fields.filter((name) => name !== field.apiName),
                    })
                  }
                />{" "}
                {field.label} <code>{field.apiName}</code>
              </label>
            ))}
          </div>
        </fieldset>
      ) : null}

      {uses.has("field") ? (
        <div className="field">
          <label htmlFor="trigger-field">Date field</label>
          <select id="trigger-field" value={trigger.field} onChange={(event) => change({ field: event.target.value })}>
            <option value="">Choose a field</option>
            {usable.map((field) => (
              <option key={field.apiName} value={field.apiName}>
                {field.label}
              </option>
            ))}
          </select>
        </div>
      ) : null}

      <div className="record-grid record-grid-two">
        {uses.has("days") ? (
          <div className="field">
            <label htmlFor="trigger-days">Days after the date</label>
            <input
              id="trigger-days"
              type="number"
              min={0}
              inputMode="numeric"
              value={trigger.days}
              onChange={(event) => change({ days: event.target.value })}
            />
          </div>
        ) : null}
        {uses.has("dayOfWeek") ? (
          <div className="field">
            <label htmlFor="trigger-day">Day of the week</label>
            <select id="trigger-day" value={trigger.dayOfWeek} onChange={(event) => change({ dayOfWeek: event.target.value })}>
              <option value="">Choose a day</option>
              {DAYS.map((day) => (
                <option key={day} value={day}>
                  {humanize(day)}
                </option>
              ))}
            </select>
          </div>
        ) : null}
        {uses.has("time") ? (
          <div className="field">
            <label htmlFor="trigger-time">Time of day</label>
            <input
              id="trigger-time"
              type="time"
              value={trigger.time}
              onChange={(event) => change({ time: event.target.value })}
            />
          </div>
        ) : null}
        {uses.has("at") ? (
          <div className="field">
            <label htmlFor="trigger-at">Date and time</label>
            <input
              id="trigger-at"
              type="datetime-local"
              value={trigger.at}
              onChange={(event) => change({ at: event.target.value })}
            />
          </div>
        ) : null}
        {uses.has("timeZone") ? (
          <div className="field">
            <label htmlFor="trigger-zone">Time zone</label>
            <input
              id="trigger-zone"
              value={trigger.timeZone}
              maxLength={60}
              placeholder="UTC"
              aria-describedby="trigger-zone-hint"
              onChange={(event) => change({ timeZone: event.target.value })}
            />
            <p className="hint" id="trigger-zone-hint">
              A name from the time zone database, for example Europe/Berlin. Left empty it is UTC.
            </p>
          </div>
        ) : null}
      </div>

      {uses.has("selection") ? (
        <details className="field" open={trigger.selection !== undefined}>
          <summary>Which records (empty: every record)</summary>
          <WorkflowSelectionEditor
            objectApiName={objectApiName}
            selection={trigger.selection}
            onChange={(selection) => change({ selection })}
          />
        </details>
      ) : null}
    </div>
  );
}
