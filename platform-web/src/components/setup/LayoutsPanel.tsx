"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { BEHAVIOUR_TEXT, LayoutPreview } from "./LayoutPreview";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];
type PageLayoutDefinition = components["schemas"]["PageLayoutDefinition"];
type Behaviour = NonNullable<components["schemas"]["LayoutField"]["behaviour"]>;

interface SectionDraft {
  label: string;
  columns: 1 | 2;
  fields: { field: string; behaviour: Behaviour }[];
}

interface Draft {
  name: string;
  label: string;
  description: string;
  sections: SectionDraft[];
}

const EMPTY: Draft = { name: "", label: "", description: "", sections: [{ label: "Details", columns: 1, fields: [] }] };

const BEHAVIOURS: Behaviour[] = ["EDITABLE", "READ_ONLY", "REQUIRED", "HIDDEN"];

function draftOf(layout: LayoutView): Draft {
  return {
    name: layout.apiName,
    label: layout.label,
    description: layout.description,
    sections: layout.definition.sections.map((section) => ({
      label: section.label,
      columns: section.columns === 2 ? 2 : 1,
      fields: section.fields.map((entry) => ({ field: entry.field, behaviour: entry.behaviour ?? "EDITABLE" })),
    })),
  };
}

function definitionOf(draft: Draft): PageLayoutDefinition {
  return {
    sections: draft.sections.map((section) => ({
      label: section.label,
      columns: section.columns,
      fields: section.fields.map((entry) => ({ field: entry.field, behaviour: entry.behaviour })),
    })),
  };
}

/** The fields a layout can show: the object's own, standard and custom ones; the system fields are the platform's. */
function showable(object: ObjectView): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

/**
 * The page layouts of one object: sections with the fields in them and how each field behaves (editable, read only,
 * required on the page, hidden). They are made, changed and removed here either live (one step in the history) or, when a
 * change set is chosen, as drafts that go live with the set. The page shows what the API answers: which fields must be
 * on a layout, and what depends on a layout, is the API's to decide, and it names the place that is wrong.
 */
export function LayoutsPanel({ object, onChanged }: { object: ObjectView; onChanged: () => void }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [layouts, setLayouts] = useState<LayoutView[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState<LayoutView | undefined>();
  const [previewing, setPreviewing] = useState<string | undefined>();

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", {
        params: { path: { objectApiName } },
      });
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setLayouts(data.data);
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
      <section aria-labelledby="layouts-heading">
        <h3 id="layouts-heading">Page layouts</h3>
        <p className="hint">The records of this object belong to the platform, so it has no page layouts.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="layouts-failed">
        {failure}
      </p>
    );
  }
  if (!layouts) {
    return <p aria-live="polite">Loading…</p>;
  }

  const fields = showable(object);
  const used = new Set(draft.sections.flatMap((section) => section.fields.map((entry) => entry.field)));

  function reset() {
    setDraft(EMPTY);
    setEditing(undefined);
  }

  function changeSection(index: number, change: Partial<SectionDraft>) {
    setDraft({
      ...draft,
      sections: draft.sections.map((section, at) => (at === index ? { ...section, ...change } : section)),
    });
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const definition = definitionOf(draft);
    void act(async () => {
      if (editing) {
        const update = {
          label: draft.label,
          description: draft.description,
          definition,
          version: editing.version,
        };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_LAYOUT",
              objectApiName,
              itemApiName: editing.apiName,
              updateLayout: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}", {
              params: { path: { objectApiName, layoutApiName: editing.apiName } },
              body: update,
            });
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The page layout was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = { name: draft.name, label: draft.label, description: draft.description, definition };
      const result = workingSet
        ? await addToSet(workingSet, { kind: "CREATE_LAYOUT", objectApiName, createLayout: request })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/layouts", {
            params: { path: { objectApiName } },
            body: request,
          });
      if (result.response.ok) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The page layout was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(layout: LayoutView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_LAYOUT", objectApiName, itemApiName: layout.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}", {
            params: { path: { objectApiName, layoutApiName: layout.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The page layout was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const formName = editing ? "Change the page layout" : "Add a page layout";
  const labelOf = (name: string) => fields.find((field) => field.apiName === name)?.label ?? name;

  return (
    <section aria-labelledby="layouts-heading" data-testid="layouts">
      <h3 id="layouts-heading">Page layouts</h3>
      <p className="hint">
        A page layout is the structure of a record form: sections with fields in them. It must show every required field of
        the object.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="layouts" />
      {layouts.length === 0 ? (
        <p>No page layouts yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Page layout</th>
              <th>API name</th>
              <th>Sections</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {layouts.map((layout) => (
              <tr key={layout.apiName} data-testid="layout-row">
                <td>
                  {layout.label}
                  {layout.description ? <div className="hint">{layout.description}</div> : null}
                </td>
                <td>
                  <code>{layout.apiName}</code>
                </td>
                <td>{layout.definition.sections.length}</td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => setPreviewing(previewing === layout.apiName ? undefined : layout.apiName)}
                    aria-expanded={previewing === layout.apiName}
                  >
                    Preview
                  </button>{" "}
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => {
                      setEditing(layout);
                      setDraft(draftOf(layout));
                    }}
                  >
                    Change
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(layout)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {layouts
        .filter((layout) => layout.apiName === previewing)
        .map((layout) => (
          <LayoutPreview key={layout.apiName} layout={layout.definition} fields={fields} title={layout.label} />
        ))}

      <h4>{formName}</h4>
      <form className="form" onSubmit={save} noValidate aria-label={formName}>
        {editing ? (
          <p>
            <code>{editing.apiName}</code>. The name of a page layout never changes.
          </p>
        ) : (
          <div className="field">
            <label htmlFor="layout-name">Name</label>
            <input
              id="layout-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
              aria-describedby="layout-name-hint"
            />
            <p className="hint" id="layout-name-hint">
              The permanent name, starting with a capital letter, for example Details. The platform adds the ending __c.
            </p>
          </div>
        )}
        <div className="field">
          <label htmlFor="layout-label">Label</label>
          <input
            id="layout-label"
            value={draft.label}
            maxLength={80}
            onChange={(event) => setDraft({ ...draft, label: event.target.value })}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="layout-description">Description</label>
          <input
            id="layout-description"
            value={draft.description}
            maxLength={500}
            onChange={(event) => setDraft({ ...draft, description: event.target.value })}
          />
        </div>

        {draft.sections.map((section, index) => (
          <fieldset className="field" key={index} data-testid="layout-section">
            <legend>Section {index + 1}</legend>
            <div className="field">
              <label htmlFor={`section-label-${index}`}>Section label</label>
              <input
                id={`section-label-${index}`}
                value={section.label}
                maxLength={80}
                onChange={(event) => changeSection(index, { label: event.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor={`section-columns-${index}`}>Columns</label>
              <select
                id={`section-columns-${index}`}
                value={section.columns}
                onChange={(event) => changeSection(index, { columns: event.target.value === "2" ? 2 : 1 })}
              >
                <option value="1">One</option>
                <option value="2">Two</option>
              </select>
            </div>
            {section.fields.map((entry, at) => (
              <div className="field" key={entry.field} data-testid="layout-field">
                <label htmlFor={`behaviour-${index}-${at}`}>
                  {labelOf(entry.field)} <code>{entry.field}</code>
                </label>
                <select
                  id={`behaviour-${index}-${at}`}
                  value={entry.behaviour}
                  onChange={(event) =>
                    changeSection(index, {
                      fields: section.fields.map((other, position) =>
                        position === at ? { ...other, behaviour: event.target.value as Behaviour } : other,
                      ),
                    })
                  }
                >
                  {BEHAVIOURS.map((behaviour) => (
                    <option key={behaviour} value={behaviour}>
                      {BEHAVIOUR_TEXT[behaviour]}
                    </option>
                  ))}
                </select>{" "}
                <button
                  type="button"
                  className="link-button"
                  onClick={() => changeSection(index, { fields: section.fields.filter((_, position) => position !== at) })}
                >
                  Take out {entry.field}
                </button>
              </div>
            ))}
            <div className="field">
              <label htmlFor={`add-field-${index}`}>Add a field to this section</label>
              <select
                id={`add-field-${index}`}
                value=""
                onChange={(event) => {
                  if (event.target.value) {
                    changeSection(index, {
                      fields: [...section.fields, { field: event.target.value, behaviour: "EDITABLE" }],
                    });
                  }
                }}
              >
                <option value="">Choose a field</option>
                {fields
                  .filter((field) => !used.has(field.apiName))
                  .map((field) => (
                    <option key={field.apiName} value={field.apiName}>
                      {field.label} ({field.apiName})
                    </option>
                  ))}
              </select>
            </div>
            {draft.sections.length > 1 ? (
              <button
                type="button"
                className="link-button"
                onClick={() => setDraft({ ...draft, sections: draft.sections.filter((_, at) => at !== index) })}
              >
                Take out section {index + 1}
              </button>
            ) : null}
          </fieldset>
        ))}
        <p>
          <button
            type="button"
            className="link-button"
            onClick={() =>
              setDraft({ ...draft, sections: [...draft.sections, { label: "", columns: 1, fields: [] }] })
            }
          >
            Add a section
          </button>
        </p>
        <div>
          <button type="submit" className="button" disabled={busy}>
            {editing ? "Save the page layout" : "Add the page layout"}
          </button>{" "}
          {editing ? (
            <button type="button" className="link-button" onClick={reset}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>
    </section>
  );
}
