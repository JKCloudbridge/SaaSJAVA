"use client";

import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { BuilderShell } from "@/components/builder/BuilderShell";
import { DropZone } from "@/components/builder/DropZone";
import { MoveButtons } from "@/components/builder/MoveButtons";
import { Palette } from "@/components/builder/Palette";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { startDrag, type DragPayload } from "@/lib/builder/dragPayload";
import {
  addSection,
  BEHAVIOURS,
  emptyLayout,
  fromDefinition,
  missingRequired,
  moveSectionTo,
  nudgeField,
  nudgeSection,
  place,
  placedFields,
  removeField,
  removeSection,
  renameSection,
  sendField,
  setBehaviour,
  setColumns,
  toDefinition,
  type Behaviour,
  type LayoutDraft,
} from "@/lib/builder/layoutModel";
import { BEHAVIOUR_TEXT, LayoutPreview } from "./LayoutPreview";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];

const SECTIONS = "sections";

/** The fields a layout can show: the object's own, standard and custom ones; the system fields are the platform's. */
function showable(object: ObjectView): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

interface Identity {
  name: string;
  label: string;
  description: string;
}

/**
 * The page layout builder: fields are dragged from the palette into sections (or added with the keyboard), sections are
 * named, ordered and given one or two columns, and each field has the behaviour it has on the form. What is dragged is
 * interface state; the draft is turned into the API's shape only on Save, then goes live (one step in the history) or
 * into the chosen change set like every other change of the object pages. The API decides whether the layout is valid
 * (for instance that every required field is on it) and its words are shown as they are.
 */
export function LayoutBuilder() {
  const params = useParams<{ objectApiName: string; layoutApiName: string }>();
  const objectApiName = params.objectApiName;
  const layoutApiName = params.layoutApiName;
  const creating = layoutApiName === "new";
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();
  const [object, setObject] = useState<ObjectView | undefined>();
  const [layout, setLayout] = useState<LayoutView | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [identity, setIdentity] = useState<Identity>({ name: "", label: "", description: "" });
  const [draft, setDraft] = useState<LayoutDraft>(emptyLayout);
  const [baseline, setBaseline] = useState("");
  const [target, setTarget] = useState<string | undefined>();
  const [previewing, setPreviewing] = useState(false);

  const snapshot = useCallback(
    (who: Identity, what: LayoutDraft) => JSON.stringify([who.label, who.description, who.name, toDefinition(what)]),
    [],
  );

  const load = useCallback(async () => {
    try {
      const path = { objectApiName };
      const one = await api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path } });
      if (!one.data) {
        setFailure(await failureText(one.error, one.response));
        return;
      }
      setObject(one.data.data);
      if (creating) {
        const fresh = emptyLayout();
        const who = { name: "", label: "", description: "" };
        setIdentity(who);
        setDraft(fresh);
        setBaseline(snapshot(who, fresh));
        return;
      }
      const found = await api.GET("/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}", {
        params: { path: { objectApiName, layoutApiName } },
      });
      if (!found.data) {
        setFailure(await failureText(found.error, found.response));
        return;
      }
      const view = found.data.data;
      const who = { name: view.apiName, label: view.label, description: view.description };
      const built = fromDefinition(view.definition);
      setLayout(view);
      setIdentity(who);
      setDraft(built);
      setBaseline(snapshot(who, built));
      setFailure(undefined);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName, layoutApiName, creating, snapshot]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const reload = useCallback(async () => {
    if (!creating) {
      await load();
    }
  }, [creating, load]);
  const { busy, notice, problem, act } = useAction(reload);

  const fields = useMemo(() => (object ? showable(object) : []), [object]);
  const byName = useMemo(() => new Map(fields.map((field) => [field.apiName, field])), [fields]);
  const placed = useMemo(() => placedFields(draft), [draft]);
  const dirty = baseline !== "" && snapshot(identity, draft) !== baseline;
  const targetId = draft.sections.some((section) => section.id === target) ? target : draft.sections[0]?.id;
  const listHref = `/setup/objects/${encodeURIComponent(objectApiName)}?section=layouts`;

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="layout-builder-failed">
        {failure}
      </p>
    );
  }
  if (!object) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (object.managedBy) {
    return <p className="hint">The records of this object belong to the platform, so it has no page layouts.</p>;
  }

  const required = fields.filter((field) => field.required).map((field) => field.apiName);
  const missing = missingRequired(draft, required);
  const labelOf = (name: string) => byName.get(name)?.label ?? name;

  function fieldOf(payload: DragPayload): string | undefined {
    if (payload.origin === "palette") {
      return payload.key.startsWith("field:") ? payload.key.slice("field:".length) : undefined;
    }
    return draft.sections.find((section) => section.id === payload.group)?.items[payload.index]?.field;
  }

  function dropOn(sectionId: string, payload: DragPayload, at?: number) {
    const field = fieldOf(payload);
    if (field) {
      setDraft((current) => place(current, sectionId, field, at));
      setTarget(sectionId);
    }
  }

  function dropOnCard(index: number, sectionId: string, payload: DragPayload) {
    if (payload.origin === "canvas" && payload.group === SECTIONS) {
      setDraft((current) => moveSectionTo(current, payload.index, index));
    } else {
      dropOn(sectionId, payload);
    }
  }

  function dropOnNewSection(payload: DragPayload) {
    const field = fieldOf(payload);
    setDraft((current) => {
      const grown = addSection(current);
      const last = grown.sections[grown.sections.length - 1];
      return field && last ? place(grown, last.id, field) : grown;
    });
  }

  function save() {
    const definition = toDefinition(draft);
    void act(async () => {
      if (!creating && layout) {
        const update = { label: identity.label, description: identity.description, definition, version: layout.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_LAYOUT",
              objectApiName,
              itemApiName: layout.apiName,
              updateLayout: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}", {
              params: { path: { objectApiName, layoutApiName: layout.apiName } },
              body: update,
            });
        if (result.response.ok && workingSet) {
          setBaseline(snapshot(identity, draft));
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The page layout was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = { name: identity.name, label: identity.label, description: identity.description, definition };
      if (workingSet) {
        const drafted = await addToSet(workingSet, { kind: "CREATE_LAYOUT", objectApiName, createLayout: request });
        if (drafted.response.ok) {
          setBaseline(snapshot(identity, draft));
          router.replace(listHref);
        }
        return {
          error: drafted.error,
          response: drafted.response,
          text: DRAFTED_TEXT,
          problem: problemText(drafted.error, drafted.response),
        };
      }
      const result = await api.POST("/api/v1/metadata/objects/{objectApiName}/layouts", {
        params: { path: { objectApiName } },
        body: request,
      });
      if (result.response.ok && result.data) {
        setBaseline(snapshot(identity, draft));
        router.replace(
          `/setup/objects/${encodeURIComponent(objectApiName)}/layouts/${encodeURIComponent(result.data.data.apiName)}`,
        );
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The page layout was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const preview = toDefinition(draft);

  return (
    <BuilderShell
      testId="layout-builder"
      crumbs={[
        { label: "Objects", href: "/setup/objects" },
        { label: object.label, href: `/setup/objects/${encodeURIComponent(objectApiName)}` },
        { label: "Page layouts", href: listHref },
        { label: creating ? "New page layout" : (identity.label || layoutApiName) },
      ]}
      title={creating ? "New page layout" : identity.label || layoutApiName}
      backHref={listHref}
      dirty={dirty}
      busy={busy}
      saveLabel={creating ? "Add the page layout" : "Save the page layout"}
      onSave={save}
      actions={
        <button
          type="button"
          className="button button-secondary"
          aria-pressed={previewing}
          onClick={() => setPreviewing(!previewing)}
        >
          {previewing ? "Hide the preview" : "Preview"}
        </button>
      }
    >
      <ActionMessages notice={notice} problem={problem} testId="layout-builder" />
      <section aria-labelledby="layout-identity-heading" className="card">
        <h3 id="layout-identity-heading">About this layout</h3>
        <div className="record-grid record-grid-two">
          {creating ? (
            <div className="field">
              <label htmlFor="layout-name">Name</label>
              <input
                id="layout-name"
                value={identity.name}
                maxLength={40}
                onChange={(event) => setIdentity({ ...identity, name: event.target.value })}
                aria-describedby="layout-name-hint"
              />
              <p className="hint" id="layout-name-hint">
                The permanent name, starting with a capital letter, for example Details. The platform adds the ending __c.
              </p>
            </div>
          ) : (
            <p>
              <code>{layout?.apiName}</code>. The name of a page layout never changes.
            </p>
          )}
          <div className="field">
            <label htmlFor="layout-label">Label</label>
            <input
              id="layout-label"
              value={identity.label}
              maxLength={80}
              onChange={(event) => setIdentity({ ...identity, label: event.target.value })}
            />
          </div>
          <div className="field span-2">
            <label htmlFor="layout-description">Description</label>
            <input
              id="layout-description"
              value={identity.description}
              maxLength={500}
              onChange={(event) => setIdentity({ ...identity, description: event.target.value })}
            />
          </div>
        </div>
      </section>

      {missing.length > 0 ? (
        <p className="form-message" role="note" data-testid="layout-missing-required">
          A layout must show every required field. Not on it yet: {missing.map(labelOf).join(", ")}.
        </p>
      ) : null}

      <div className="builder">
        <Palette
          label="Fields"
          searchable
          categories={[
            {
              id: "fields",
              label: "Fields",
              empty: "This object has no fields to place.",
              items: fields.map((field) => ({
                key: `field:${field.apiName}`,
                label: field.required ? `${field.label} *` : field.label,
                title: `${field.label} (${field.apiName})`,
                used: placed.has(field.apiName),
              })),
            },
          ]}
          onPick={(item) => {
            const field = item.key.slice("field:".length);
            if (targetId) {
              setDraft((current) => place(current, targetId, field));
            }
          }}
        />

        <div className="canvas" data-testid="layout-canvas">
          {draft.sections.map((section, index) => (
            <DropZone
              key={section.id}
              as="section"
              className={["canvas-card", section.id === targetId ? "is-target" : ""].filter(Boolean).join(" ")}
              aria-label={`Section ${section.label || index + 1}`}
              data-testid="layout-section"
              onDropPayload={(payload: DragPayload) => dropOnCard(index, section.id, payload)}
            >
              <div className="canvas-card-head" onClick={() => setTarget(section.id)}>
                <span
                  className="drag-handle"
                  draggable
                  title="Drag to reorder the section"
                  aria-hidden="true"
                  onDragStart={(event) => startDrag(event, { origin: "canvas", group: SECTIONS, index })}
                >
                  ⠿
                </span>
                <input
                  className="canvas-title"
                  aria-label={`Label of section ${index + 1}`}
                  value={section.label}
                  maxLength={80}
                  placeholder="Section label"
                  onFocus={() => setTarget(section.id)}
                  onChange={(event) => setDraft(renameSection(draft, section.id, event.target.value))}
                />
                {section.id === targetId ? <span className="badge badge-on">Adds go here</span> : null}
                <label className="visually-hidden" htmlFor={`columns-${section.id}`}>
                  Columns of section {index + 1}
                </label>
                <select
                  id={`columns-${section.id}`}
                  value={section.columns}
                  onChange={(event) => setDraft(setColumns(draft, section.id, event.target.value === "2" ? 2 : 1))}
                >
                  <option value="1">One column</option>
                  <option value="2">Two columns</option>
                </select>
                <MoveButtons
                  name={`section ${index + 1}`}
                  onUp={index > 0 ? () => setDraft(nudgeSection(draft, section.id, -1)) : undefined}
                  onDown={index < draft.sections.length - 1 ? () => setDraft(nudgeSection(draft, section.id, 1)) : undefined}
                  onRemove={draft.sections.length > 1 ? () => setDraft(removeSection(draft, section.id)) : undefined}
                />
              </div>
              <DropZone
                className="canvas-items"
                data-testid="layout-section-items"
                onDropPayload={(payload: DragPayload) => dropOn(section.id, payload)}
              >
                {section.items.length === 0 ? (
                  <p className="canvas-empty">Drag fields here, or choose a field in the palette.</p>
                ) : null}
                {section.items.map((item, at) => (
                  <DropZone
                    key={item.field}
                    as="div"
                    className="item-chip"
                    data-testid="layout-field"
                    onDropPayload={(payload: DragPayload) => dropOn(section.id, payload, at)}
                  >
                    <span
                      className="drag-handle"
                      draggable
                      title="Drag to move the field"
                      aria-hidden="true"
                      onDragStart={(event) => startDrag(event, { origin: "canvas", group: section.id, index: at })}
                    >
                      ⠿
                    </span>
                    <span>
                      {labelOf(item.field)}
                      {byName.get(item.field)?.required ? " *" : ""}
                    </span>
                    <label className="visually-hidden" htmlFor={`behaviour-${section.id}-${item.field}`}>
                      Behaviour of {labelOf(item.field)}
                    </label>
                    <select
                      id={`behaviour-${section.id}-${item.field}`}
                      value={item.behaviour}
                      onChange={(event) => setDraft(setBehaviour(draft, item.field, event.target.value as Behaviour))}
                    >
                      {BEHAVIOURS.map((behaviour) => (
                        <option key={behaviour} value={behaviour}>
                          {BEHAVIOUR_TEXT[behaviour]}
                        </option>
                      ))}
                    </select>
                    {draft.sections.length > 1 ? (
                      <>
                        <label className="visually-hidden" htmlFor={`send-${section.id}-${item.field}`}>
                          Section of {labelOf(item.field)}
                        </label>
                        <select
                          id={`send-${section.id}-${item.field}`}
                          value={section.id}
                          onChange={(event) => setDraft(sendField(draft, item.field, event.target.value))}
                        >
                          {draft.sections.map((other, number) => (
                            <option key={other.id} value={other.id}>
                              {other.label || `Section ${number + 1}`}
                            </option>
                          ))}
                        </select>
                      </>
                    ) : null}
                    <MoveButtons
                      name={labelOf(item.field)}
                      upLabel="earlier"
                      downLabel="later"
                      onUp={at > 0 ? () => setDraft(nudgeField(draft, section.id, item.field, -1)) : undefined}
                      onDown={
                        at < section.items.length - 1
                          ? () => setDraft(nudgeField(draft, section.id, item.field, 1))
                          : undefined
                      }
                      onRemove={() => setDraft(removeField(draft, item.field))}
                    />
                  </DropZone>
                ))}
              </DropZone>
            </DropZone>
          ))}
          <DropZone
            as="button"
            type="button"
            className="add-dashed"
            onClick={() => {
              const grown = addSection(draft);
              setDraft(grown);
              setTarget(grown.sections[grown.sections.length - 1]?.id);
            }}
            onDropPayload={dropOnNewSection}
          >
            + Add a section (or drop a field here to start one)
          </DropZone>
        </div>
      </div>

      {previewing ? (
        <LayoutPreview layout={preview} fields={fields} title={identity.label || "Page layout"} />
      ) : null}
    </BuilderShell>
  );
}
