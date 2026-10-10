"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { FieldForm } from "./FieldForm";
import { KIND_TEXT, problemText, settingsSummary, type FieldTypeView, type FieldView } from "./objectText";
import { RecordTypesPanel } from "./RecordTypesPanel";
import { RelationshipsPanel } from "./RelationshipsPanel";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

interface Loaded {
  object: ObjectView;
  types: FieldTypeView[];
  objects: ObjectSummary[];
}

/**
 * One object of the organization with its fields: the labels of a custom object, the fields in the order system, standard,
 * own, and the form that adds or changes an own field. The page shows what the API answers and its own words for every
 * refusal; whether something may be changed is a flag the API sends (editable, extensible), and the API checks it again
 * on every call.
 */
export function ObjectDetailPanel() {
  const params = useParams<{ objectApiName: string }>();
  const objectApiName = params.objectApiName;
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [editingField, setEditingField] = useState<FieldView | undefined>();
  const [labels, setLabels] = useState({ label: "", pluralLabel: "", description: "" });
  const [confirmRemove, setConfirmRemove] = useState(false);

  const reload = useCallback(async () => {
    try {
      const one = await api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } } });
      if (!one.data) {
        setFailure(await failureText(one.error, one.response));
        return;
      }
      const types = await api.GET("/api/v1/metadata/field-types");
      const objects = await api.GET("/api/v1/metadata/objects");
      if (!types.data || !objects.data) {
        setFailure(await failureText(types.error ?? objects.error, (types.data ? objects.response : types.response)));
        return;
      }
      setFailure(undefined);
      setLoaded({ object: one.data.data, types: types.data.data, objects: objects.data.data });
      setLabels({ label: one.data.data.label, pluralLabel: one.data.data.pluralLabel, description: one.data.data.description });
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  function saveLabels(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!loaded) {
      return;
    }
    const body = { ...labels, version: loaded.object.version };
    void act(async () => {
      const { error, response } = workingSet
        ? await addToSet(workingSet, { kind: "UPDATE_OBJECT", objectApiName, updateObject: body })
        : await api.PUT("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } }, body });
      return {
        error,
        response,
        text: workingSet ? DRAFTED_TEXT : "The object was saved.",
        problem: problemText(error, response),
      };
    });
  }

  function removeObject() {
    void act(async () => {
      const { error, response } = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_OBJECT", objectApiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } } });
      if (response.ok && !workingSet) {
        router.push("/setup/objects");
      }
      return {
        error,
        response,
        text: workingSet ? DRAFTED_TEXT : "The object was removed.",
        problem: problemText(error, response),
      };
    });
    setConfirmRemove(false);
  }

  function removeField(field: FieldView) {
    void act(async () => {
      const { error, response } = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_FIELD", objectApiName, itemApiName: field.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}", {
            params: { path: { objectApiName, fieldApiName: field.apiName } },
          });
      return {
        error,
        response,
        text: workingSet ? DRAFTED_TEXT : "The field was removed.",
        problem: problemText(error, response),
      };
    });
  }

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="object-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }
  const { object, types, objects } = loaded;

  return (
    <div className="stack">
      <p>
        <Link href="/setup/objects">All objects</Link>
      </p>
      <h2>
        {object.label} <code>{object.apiName}</code>
      </h2>
      <p>
        {KIND_TEXT[object.kind] ?? object.kind} object.
        {object.managedBy ? ` Its records are managed by ${object.managedBy}.` : ""}
        {object.description ? ` ${object.description}` : ""}
      </p>
      <ActionMessages notice={notice} problem={problem} testId="object" />

      {object.editable ? (
        <section aria-labelledby="object-edit-heading">
          <h3 id="object-edit-heading">Labels</h3>
          <form className="form" onSubmit={saveLabels} noValidate>
            <div className="field">
              <label htmlFor="edit-label">Label</label>
              <input
                id="edit-label"
                value={labels.label}
                maxLength={80}
                onChange={(event) => setLabels({ ...labels, label: event.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor="edit-plural">Plural label</label>
              <input
                id="edit-plural"
                value={labels.pluralLabel}
                maxLength={80}
                onChange={(event) => setLabels({ ...labels, pluralLabel: event.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor="edit-description">Description</label>
              <input
                id="edit-description"
                value={labels.description}
                maxLength={500}
                onChange={(event) => setLabels({ ...labels, description: event.target.value })}
              />
            </div>
            <button type="submit" className="button" disabled={busy}>
              Save the labels
            </button>
          </form>
          {confirmRemove ? (
            <p>
              Remove this object and all its fields? The permissions on them end too.{" "}
              <button type="button" className="button" disabled={busy} onClick={removeObject}>
                Yes, remove the object
              </button>{" "}
              <button type="button" className="link-button" onClick={() => setConfirmRemove(false)}>
                Cancel
              </button>
            </p>
          ) : (
            <p>
              <button type="button" className="link-button" disabled={busy} onClick={() => setConfirmRemove(true)}>
                Remove the object
              </button>
            </p>
          )}
        </section>
      ) : (
        <p className="hint">This object is defined by the platform. Its definition cannot be changed.</p>
      )}

      <section aria-labelledby="fields-heading">
        <h3 id="fields-heading">Fields</h3>
        <table className="table" data-testid="fields">
          <thead>
            <tr>
              <th>Field</th>
              <th>API name</th>
              <th>Type</th>
              <th>Kind</th>
              <th>Details</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {object.fields.map((field) => (
              <tr key={field.apiName} data-testid="field-row">
                <td>
                  {field.label}
                  {field.retired ? <span className="hint"> (retired)</span> : null}
                  {field.description ? <div className="hint">{field.description}</div> : null}
                </td>
                <td>
                  <code>{field.apiName}</code>
                </td>
                <td>{types.find((type) => type.type === field.type)?.label ?? field.type}</td>
                <td>{KIND_TEXT[field.kind] ?? field.kind}</td>
                <td>
                  {[field.required ? "required" : "", field.unique ? "unique" : "", settingsSummary(field)]
                    .filter(Boolean)
                    .join("; ")}
                  {field.defaultValue ? <div className="hint">default {field.defaultValue}</div> : null}
                </td>
                <td>
                  {field.editable ? (
                    <>
                      <button type="button" className="link-button" disabled={busy} onClick={() => setEditingField(field)}>
                        Change
                      </button>{" "}
                      <button type="button" className="link-button" disabled={busy} onClick={() => removeField(field)}>
                        Remove
                      </button>
                    </>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {object.extensible ? (
        <section aria-labelledby="field-form-heading">
          <h3 id="field-form-heading">{editingField ? "Change the field" : "Add a field"}</h3>
          <FieldForm
            key={editingField?.apiName ?? "new"}
            types={types}
            objects={objects}
            editing={editingField}
            busy={busy}
            onCancel={() => setEditingField(undefined)}
            onCreate={(body) =>
              void act(async () => {
                const { error, response } = workingSet
                  ? await addToSet(workingSet, { kind: "CREATE_FIELD", objectApiName, createField: body })
                  : await api.POST("/api/v1/metadata/objects/{objectApiName}/fields", {
                      params: { path: { objectApiName } },
                      body,
                    });
                return {
                  error,
                  response,
                  text: workingSet ? DRAFTED_TEXT : "The field was added.",
                  problem: problemText(error, response),
                };
              })
            }
            onUpdate={(body) =>
              void act(async () => {
                const fieldApiName = editingField?.apiName ?? "";
                const { error, response } = workingSet
                  ? await addToSet(workingSet, {
                      kind: "UPDATE_FIELD",
                      objectApiName,
                      itemApiName: fieldApiName,
                      updateField: body,
                    })
                  : await api.PUT("/api/v1/metadata/objects/{objectApiName}/fields/{fieldApiName}", {
                      params: { path: { objectApiName, fieldApiName } },
                      body,
                    });
                if (response.ok) {
                  setEditingField(undefined);
                }
                return {
                  error,
                  response,
                  text: workingSet ? DRAFTED_TEXT : "The field was saved.",
                  problem: problemText(error, response),
                };
              })
            }
          />
        </section>
      ) : (
        <p className="hint">The platform does not allow extra fields on this object.</p>
      )}

      <RelationshipsPanel objectApiName={objectApiName} />
      <RecordTypesPanel object={object} onChanged={reload} />
    </div>
  );
}
