"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { emptyInput, isEmptyInput, valueFromInput, hintFor } from "@/lib/records/fieldValue";
import { stringifyBody } from "@/lib/records/json";
import type { BulkResponse, FieldInput, FormField, ObjectForm, RecordView } from "@/lib/records/types";
import { FieldEditor } from "./FieldRegistry";

/** A selected record and the words that name it in a report. */
export interface Picked {
  record: RecordView;
  label: string;
}

/** What a bulk call came to, kept for the report: the response and the records in the order they were sent. */
export interface BulkReport {
  operation: "UPDATE" | "DELETE";
  response: BulkResponse;
  picked: Picked[];
}

const JSON_HEADERS = { "Content-Type": "application/json" };

/** The fields a bulk change may name: ones a person types, that need not be different on every record. */
function changeable(form: ObjectForm): FormField[] {
  return form.fields.filter((field) => field.editable && !field.unique && !field.externalId && !field.calculated);
}

interface Change {
  key: number;
  field: string;
  input: FieldInput;
  clear: boolean;
}

/**
 * Changes the same fields on every selected record (Sprint 19, ADR-0129; the call is `POST .../records/bulk`, Sprint 17).
 * Each record goes through exactly the steps a single save would, so a record the member may not change, a value a rule
 * refuses or a record somebody changed since the list was read fails by itself, with the server's reason, and the others
 * are saved (or, with "all or none", none is). The report that follows names each record that was refused.
 */
export function BulkEditPanel({
  objectApiName,
  form,
  picked,
  onDone,
  onCancel,
}: {
  objectApiName: string;
  form: ObjectForm;
  picked: Picked[];
  onDone: (report: BulkReport) => void;
  onCancel: () => void;
}) {
  const fields = changeable(form);
  const [changes, setChanges] = useState<Change[]>(() => {
    const first = fields[0];
    return first ? [{ key: 1, field: first.apiName, input: emptyInput(first), clear: false }] : [];
  });
  const [allOrNone, setAllOrNone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();
  const byName = new Map(fields.map((field) => [field.apiName, field]));

  function patch(key: number, change: Partial<Change>) {
    setChanges((current) => current.map((entry) => (entry.key === key ? { ...entry, ...change } : entry)));
  }

  async function apply() {
    setProblem(undefined);
    const set: Record<string, unknown> = {};
    const clear: string[] = [];
    for (const change of changes) {
      const field = byName.get(change.field);
      if (!field) {
        continue;
      }
      if (change.clear) {
        clear.push(field.apiName);
        continue;
      }
      const hint = hintFor({ ...field, required: true }, change.input);
      if (hint || isEmptyInput(field, change.input)) {
        setProblem(`${field.label}: ${hint ?? "Enter a value, or choose to clear the field."}`);
        return;
      }
      set[field.apiName] = valueFromInput(field, change.input);
    }
    if (Object.keys(set).length === 0 && clear.length === 0) {
      setProblem("Choose a field to change.");
      return;
    }
    setBusy(true);
    const outcome = await call(
      () =>
        api.POST("/api/v1/objects/{objectApiName}/records/bulk", {
          params: { path: { objectApiName } },
          body: {
            operation: "UPDATE",
            allOrNone,
            records: picked.map((entry) => ({ id: entry.record.id, version: entry.record.version, fields: set, clear })),
          },
          bodySerializer: stringifyBody,
          headers: JSON_HEADERS,
        }),
    );
    setBusy(false);
    if (outcome.ok) {
      onDone({ operation: "UPDATE", response: outcome.data.data, picked });
    } else {
      setProblem(outcome.failure.message);
    }
  }

  return (
    <section aria-labelledby="bulk-edit-heading" data-testid="bulk-edit-panel">
      <h2 id="bulk-edit-heading">
        Change {picked.length} {picked.length === 1 ? "record" : "records"}
      </h2>
      <div className="stack">
        {changes.map((change, index) => {
          const field = byName.get(change.field);
          return (
            <div key={change.key} className="bulk-change" role="group" aria-label={`Change ${index + 1}`}>
              <select
                aria-label="Field to change"
                value={change.field}
                onChange={(event) => {
                  const next = byName.get(event.target.value);
                  if (next) {
                    patch(change.key, { field: next.apiName, input: emptyInput(next), clear: false });
                  }
                }}
              >
                {fields.map((option) => (
                  <option key={option.apiName} value={option.apiName}>
                    {option.label}
                  </option>
                ))}
              </select>
              {field && !change.clear ? (
                <FieldEditor
                  objectApiName={objectApiName}
                  field={{ ...field, required: false, description: "" }}
                  input={change.input}
                  problems={[]}
                  onBlur={() => undefined}
                  onChange={(input) => patch(change.key, { input })}
                />
              ) : null}
              <label>
                <input
                  type="checkbox"
                  checked={change.clear}
                  onChange={(event) => patch(change.key, { clear: event.target.checked })}
                />
                Clear this field on every record
              </label>
              {changes.length > 1 ? (
                <button type="button" className="link-button" onClick={() => setChanges((current) => current.filter((entry) => entry.key !== change.key))}>
                  Remove
                </button>
              ) : null}
            </div>
          );
        })}
        <div>
          <button
            type="button"
            className="button button-secondary"
            disabled={changes.length >= fields.length}
            onClick={() => {
              const used = new Set(changes.map((change) => change.field));
              const next = fields.find((field) => !used.has(field.apiName));
              if (next) {
                setChanges((current) => [...current, { key: Math.max(0, ...current.map((entry) => entry.key)) + 1, field: next.apiName, input: emptyInput(next), clear: false }]);
              }
            }}
          >
            Change another field
          </button>
        </div>
        <label>
          <input type="checkbox" checked={allOrNone} onChange={(event) => setAllOrNone(event.target.checked)} />
          Save all or none: if one record cannot be changed, change nothing
        </label>
      </div>
      {problem ? (
        <p className="form-message" role="alert">
          {problem}
        </p>
      ) : null}
      <div className="filters-actions">
        <button type="button" className="button" disabled={busy || fields.length === 0} onClick={() => void apply()}>
          {busy ? "Saving…" : `Change ${picked.length} ${picked.length === 1 ? "record" : "records"}`}
        </button>
        <button type="button" className="button button-secondary" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </section>
  );
}

/**
 * Moves the selected records to the recycle bin (they can be restored for the retention period). Asks first, once; each
 * record is judged by the same steps as a single delete, so one the member may not delete fails by itself.
 */
export function BulkDeletePanel({
  objectApiName,
  picked,
  onDone,
  onCancel,
}: {
  objectApiName: string;
  picked: Picked[];
  onDone: (report: BulkReport) => void;
  onCancel: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();

  async function remove() {
    setBusy(true);
    setProblem(undefined);
    const outcome = await call(
      () =>
        api.POST("/api/v1/objects/{objectApiName}/records/bulk", {
          params: { path: { objectApiName } },
          body: { operation: "DELETE", allOrNone: false, records: picked.map((entry) => ({ id: entry.record.id })) },
        }),
    );
    setBusy(false);
    if (outcome.ok) {
      onDone({ operation: "DELETE", response: outcome.data.data, picked });
    } else {
      setProblem(outcome.failure.message);
    }
  }

  return (
    <section aria-labelledby="bulk-delete-heading" data-testid="bulk-delete-panel">
      <h2 id="bulk-delete-heading">
        Delete {picked.length} {picked.length === 1 ? "record" : "records"}?
      </h2>
      <p>They go to the recycle bin and can be brought back from there for a while.</p>
      {problem ? (
        <p className="form-message" role="alert">
          {problem}
        </p>
      ) : null}
      <div className="filters-actions">
        <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove()}>
          {busy ? "Deleting…" : `Delete ${picked.length} ${picked.length === 1 ? "record" : "records"}`}
        </button>
        <button type="button" className="button button-secondary" disabled={busy} onClick={onCancel}>
          Keep them
        </button>
      </div>
    </section>
  );
}

/** What a bulk call did, in words: how many worked, and each record that was refused with the server's reason. */
export function BulkSummary({ report, form, onClose }: { report: BulkReport; form: ObjectForm; onClose: () => void }) {
  const { response, picked, operation } = report;
  const failed = response.results.filter((result) => result.status === "FAILED" || result.status === "ROLLED_BACK");
  const labels = new Map(form.fields.map((field) => [field.apiName, field.label]));
  const verb = operation === "DELETE" ? "deleted" : "changed";
  return (
    <section aria-labelledby="bulk-result-heading" data-testid="bulk-summary">
      <h2 id="bulk-result-heading">{operation === "DELETE" ? "Delete" : "Change"} finished</h2>
      <p role={response.failed ? "alert" : "status"} className={response.failed ? "form-message" : undefined}>
        {response.committed === false
          ? `Nothing was ${verb}: one record could not be ${verb}, and you asked for all or none.`
          : `${response.succeeded ?? 0} ${(response.succeeded ?? 0) === 1 ? "record" : "records"} ${verb}${
              response.failed ? `, ${response.failed} not` : ""
            }.`}
      </p>
      {failed.length > 0 ? (
        <ul data-testid="bulk-failures">
          {failed.map((result, index) => (
            <li key={result.position ?? index}>
              <strong>{picked[result.position ?? -1]?.label ?? `Record ${(result.position ?? index) + 1}`}</strong>:{" "}
              {result.status === "ROLLED_BACK"
                ? "Not changed, because another record failed."
                : result.code === "CONCURRENT_MODIFICATION"
                  ? "Changed by someone else since this list was loaded. Reload the list and try again."
                  : (result.message ?? "Not saved.")}
              {result.fields
                ? Object.entries(result.fields).map(([place, messages]) => (
                    <span key={place} className="hint">
                      {" "}
                      {labels.get(place) ?? place}: {messages.join(" ")}
                    </span>
                  ))
                : null}
            </li>
          ))}
        </ul>
      ) : null}
      <button type="button" className="button button-secondary" onClick={onClose}>
        Close
      </button>
    </section>
  );
}
