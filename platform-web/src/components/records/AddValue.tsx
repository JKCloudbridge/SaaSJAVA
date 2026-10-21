"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import type { AddedValue } from "@/lib/records/dependency";
import type { FormField } from "@/lib/records/types";

/**
 * Adds a value to a picklist whose list the organization owns, from the form where a record is written (FA2A, ADR-0182):
 * a short code, what people read, and for a dependent picklist the values of the other field it goes with. It is drawn
 * only when the API said the member may (`canAddValues`); the API checks it again and answers in its own words. It only
 * adds: it cannot rename, switch off or remove a value.
 *
 * @param controlling the other picklist this one depends on, with the values to tick, or none
 * @param chosen the value of the other picklist chosen now, ticked at first
 */
export function AddValue({
  objectApiName,
  field,
  controlling,
  chosen,
  onAdded,
}: {
  objectApiName: string;
  field: FormField;
  controlling?: { label: string; values: Array<{ value: string; label: string }> };
  chosen: string;
  onAdded: (added: AddedValue) => void;
}) {
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [goesWith, setGoesWith] = useState<string[]>(chosen === "" ? [] : [chosen]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>();

  if (!open) {
    return (
      <button
        type="button"
        className="link-button"
        onClick={() => {
          setGoesWith(chosen === "" ? [] : [chosen]);
          setOpen(true);
        }}
      >
        Add a value
      </button>
    );
  }

  async function add() {
    setBusy(true);
    setProblem(undefined);
    try {
      const { data, error, response } = await api.POST(
        "/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}/values",
        {
          params: { path: { objectApiName, fieldApiName: field.apiName } },
          body: {
            value: code,
            label: label === "" ? undefined : label,
            controllingValues: controlling ? goesWith : undefined,
          },
        },
      );
      if (response.ok && data) {
        onAdded({
          value: data.data.value,
          label: data.data.label,
          controllingValues: data.data.controllingValues,
        });
        setOpen(false);
        setCode("");
        setLabel("");
        return;
      }
      const failure = failureFromResponse(error, response);
      const lines = Object.values(failure.fields ?? {}).flat();
      setProblem(lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message);
    } finally {
      setBusy(false);
    }
  }

  const id = `add-${field.apiName}`;
  return (
    <fieldset className="field" aria-label={`Add a value to ${field.label}`} data-testid="add-value">
      <legend>Add a value to {field.label}</legend>
      <div className="field">
        <label htmlFor={`${id}-code`}>{field.codeValues ? "Code" : "Value"}</label>
        <input
          id={`${id}-code`}
          value={code}
          maxLength={field.codeValues ? 10 : 80}
          onChange={(event) => setCode(event.target.value)}
          autoComplete="off"
        />
        {field.codeValues ? (
          <p className="hint">Up to 10 letters, digits, hyphens or underscores. It is kept in capitals.</p>
        ) : null}
      </div>
      <div className="field">
        <label htmlFor={`${id}-label`}>What people read (optional)</label>
        <input id={`${id}-label`} value={label} maxLength={80} onChange={(event) => setLabel(event.target.value)} />
      </div>
      {controlling ? (
        <div className="field">
          <span className="field-label" id={`${id}-with`}>
            Goes with these values of {controlling.label}
          </span>
          <div className="check-list" role="group" aria-labelledby={`${id}-with`}>
            {controlling.values.map((one) => (
              <label key={one.value}>
                <input
                  type="checkbox"
                  checked={goesWith.includes(one.value)}
                  onChange={(event) =>
                    setGoesWith(
                      event.target.checked ? [...goesWith, one.value] : goesWith.filter((value) => value !== one.value),
                    )
                  }
                />
                {one.label || one.value}
              </label>
            ))}
          </div>
        </div>
      ) : null}
      {problem ? (
        <p className="form-message" role="alert">
          {problem}
        </p>
      ) : null}
      <button
        type="button"
        className="button"
        disabled={busy || code.trim() === "" || (controlling !== undefined && goesWith.length === 0)}
        onClick={() => void add()}
      >
        Add
      </button>{" "}
      <button type="button" className="link-button" onClick={() => setOpen(false)}>
        Cancel
      </button>
    </fieldset>
  );
}
