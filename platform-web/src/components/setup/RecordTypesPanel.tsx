"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type RecordTypeView = components["schemas"]["RecordTypeView"];
type CreateRecordTypeRequest = components["schemas"]["CreateRecordTypeRequest"];

interface Draft {
  name: string;
  label: string;
  description: string;
  active: boolean;
  defaultType: boolean;
  allFields: boolean;
  fields: string[];
  /** The values a picklist is limited to; a picklist that is not here allows all its values. */
  limits: Record<string, string[]>;
}

const EMPTY: Draft = {
  name: "",
  label: "",
  description: "",
  active: true,
  defaultType: false,
  allFields: true,
  fields: [],
  limits: {},
};

function draftOf(type: RecordTypeView): Draft {
  return {
    name: type.apiName,
    label: type.label,
    description: type.description,
    active: type.active,
    defaultType: type.defaultType,
    allFields: type.allFields,
    fields: type.availableFields,
    limits: Object.fromEntries(type.picklistSubsets.map((subset) => [subset.field, subset.values])),
  };
}

function toggle(list: string[], value: string, on: boolean): string[] {
  return on ? [...list.filter((item) => item !== value), value] : list.filter((item) => item !== value);
}

/** The fields a record type can offer: the object's own, standard and custom ones; the system fields are always there. */
function offerable(object: ObjectView): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

function activeValues(field: FieldView): string[] {
  return (field.settings.values ?? []).filter((value) => value.active).map((value) => value.value);
}

/**
 * The record types of one object: variants that offer some of its fields and allow some of its picklist values. They are
 * made, changed and removed here either live (one step in the history) or, when a change set is chosen, as drafts that go
 * live with the set. The page shows what the API answers; which fields and values are allowed, and what depends on what,
 * is the API's to decide, and it names the dependent when something cannot go.
 */
export function RecordTypesPanel({ object, onChanged }: { object: ObjectView; onChanged: () => void }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [types, setTypes] = useState<RecordTypeView[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState<RecordTypeView | undefined>();

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/record-types", {
        params: { path: { objectApiName } },
      });
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setTypes(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(reload);
    }
  }, [reload, managed]);

  const done = useCallback(async () => {
    await reload();
    onChanged();
  }, [reload, onChanged]);
  const { busy, notice, problem, act } = useAction(done);

  if (object.managedBy) {
    return (
      <section aria-labelledby="record-types-heading">
        <h3 id="record-types-heading">Record types</h3>
        <p className="hint">The records of this object belong to the platform, so it has no record types.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="record-types-failed">
        {failure}
      </p>
    );
  }
  if (!types) {
    return <p aria-live="polite">Loading…</p>;
  }

  const fields = offerable(object);
  const picklists = fields.filter((field) => field.settings.values != null);

  function body(): CreateRecordTypeRequest {
    const available = draft.allFields ? undefined : draft.fields;
    const subsets = picklists
      .filter((field) => draft.limits[field.apiName] && (draft.allFields || draft.fields.includes(field.apiName)))
      .map((field) => ({ field: field.apiName, values: draft.limits[field.apiName] ?? [] }));
    return {
      name: draft.name,
      label: draft.label,
      description: draft.description,
      active: draft.active,
      defaultType: draft.defaultType,
      availableFields: available,
      picklistSubsets: subsets,
    };
  }

  function reset() {
    setDraft(EMPTY);
    setEditing(undefined);
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const request = body();
    void act(async () => {
      if (editing) {
        const update = { ...request, version: editing.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_RECORD_TYPE",
              objectApiName,
              itemApiName: editing.apiName,
              updateRecordType: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/record-types/{recordTypeApiName}", {
              params: { path: { objectApiName, recordTypeApiName: editing.apiName } },
              body: update,
            });
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The record type was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const result = workingSet
        ? await addToSet(workingSet, { kind: "CREATE_RECORD_TYPE", objectApiName, createRecordType: request })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/record-types", {
            params: { path: { objectApiName } },
            body: request,
          });
      if (result.response.ok) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record type was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(type: RecordTypeView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_RECORD_TYPE", objectApiName, itemApiName: type.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/record-types/{recordTypeApiName}", {
            params: { path: { objectApiName, recordTypeApiName: type.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record type was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const formName = editing ? "Change the record type" : "Add a record type";

  return (
    <section aria-labelledby="record-types-heading" data-testid="record-types">
      <h3 id="record-types-heading">Record types</h3>
      <p className="hint">
        A record type is a variant of this object (for example New business and Renewal): it offers some of the fields and
        allows some of the values of a picklist.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="record-types" />
      {types.length === 0 ? (
        <p>No record types yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Record type</th>
              <th>API name</th>
              <th>Fields</th>
              <th>Status</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {types.map((type) => (
              <tr key={type.apiName} data-testid="record-type-row">
                <td>
                  {type.label}
                  {type.description ? <div className="hint">{type.description}</div> : null}
                </td>
                <td>
                  <code>{type.apiName}</code>
                </td>
                <td>
                  {type.allFields ? "Every field" : `${type.availableFields.length} fields`}
                  {type.picklistSubsets.length > 0 ? (
                    <div className="hint">
                      {type.picklistSubsets.length} picklist{type.picklistSubsets.length === 1 ? "" : "s"} limited
                    </div>
                  ) : null}
                </td>
                <td>
                  {type.active ? "Active" : "Not active"}
                  {type.defaultType ? <div className="hint">The default</div> : null}
                </td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => {
                      setEditing(type);
                      setDraft(draftOf(type));
                    }}
                  >
                    Change
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(type)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h4>{formName}</h4>
      <form className="form" onSubmit={save} noValidate aria-label={formName}>
        {editing ? (
          <p>
            <code>{editing.apiName}</code>. The name of a record type never changes.
          </p>
        ) : (
          <div className="field">
            <label htmlFor="record-type-name">Name</label>
            <input
              id="record-type-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
              aria-describedby="record-type-name-hint"
            />
            <p className="hint" id="record-type-name-hint">
              The permanent name, starting with a capital letter, for example Renewal. The platform adds the ending __c.
            </p>
          </div>
        )}
        <div className="field">
          <label htmlFor="record-type-label">Label</label>
          <input
            id="record-type-label"
            value={draft.label}
            maxLength={80}
            onChange={(event) => setDraft({ ...draft, label: event.target.value })}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="record-type-description">Description</label>
          <input
            id="record-type-description"
            value={draft.description}
            maxLength={500}
            onChange={(event) => setDraft({ ...draft, description: event.target.value })}
          />
        </div>
        <label>
          <input
            type="checkbox"
            checked={draft.active}
            onChange={(event) => setDraft({ ...draft, active: event.target.checked })}
          />{" "}
          Active
        </label>
        <label>
          <input
            type="checkbox"
            checked={draft.defaultType}
            onChange={(event) => setDraft({ ...draft, defaultType: event.target.checked })}
          />{" "}
          The default record type of this object
        </label>
        <label>
          <input
            type="checkbox"
            checked={draft.allFields}
            onChange={(event) => setDraft({ ...draft, allFields: event.target.checked })}
          />{" "}
          Offer every field
        </label>
        {draft.allFields ? null : (
          <fieldset className="field">
            <legend>Fields this record type offers</legend>
            {fields.map((field) => (
              <label key={field.apiName}>
                <input
                  type="checkbox"
                  checked={draft.fields.includes(field.apiName)}
                  onChange={(event) =>
                    setDraft({ ...draft, fields: toggle(draft.fields, field.apiName, event.target.checked) })
                  }
                />{" "}
                {field.label} <code>{field.apiName}</code>
                {field.required ? <span className="hint"> (required: it must be offered)</span> : null}
              </label>
            ))}
          </fieldset>
        )}
        {picklists
          .filter((field) => draft.allFields || draft.fields.includes(field.apiName))
          .map((field) => {
            const limited = draft.limits[field.apiName];
            return (
              <fieldset className="field" key={field.apiName}>
                <legend>
                  {field.label} <code>{field.apiName}</code>
                </legend>
                <label>
                  <input
                    type="checkbox"
                    checked={limited !== undefined}
                    onChange={(event) => {
                      const limits = { ...draft.limits };
                      if (event.target.checked) {
                        limits[field.apiName] = activeValues(field);
                      } else {
                        delete limits[field.apiName];
                      }
                      setDraft({ ...draft, limits });
                    }}
                  />{" "}
                  Limit the values of {field.label}
                </label>
                {limited === undefined
                  ? null
                  : activeValues(field).map((value) => (
                      <label key={value}>
                        <input
                          type="checkbox"
                          checked={limited.includes(value)}
                          onChange={(event) =>
                            setDraft({
                              ...draft,
                              limits: { ...draft.limits, [field.apiName]: toggle(limited, value, event.target.checked) },
                            })
                          }
                        />{" "}
                        {value}
                      </label>
                    ))}
              </fieldset>
            );
          })}
        <button type="submit" className="button" disabled={busy}>
          {editing ? "Save the record type" : "Add the record type"}
        </button>{" "}
        {editing ? (
          <button type="button" className="link-button" onClick={reset}>
            Cancel
          </button>
        ) : null}
      </form>
    </section>
  );
}
