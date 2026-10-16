"use client";

import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { api } from "@/lib/api/client";
import { call, problemsOf } from "@/lib/records/call";
import { compareChanges, type Conflict } from "@/lib/records/conflict";
import {
  defaultInput,
  formatValue,
  hintFor,
  inputFromValue,
  isEmptyInput,
  recordValue,
  sameInput,
  valueFromInput,
} from "@/lib/records/fieldValue";
import { useLookupLabels } from "@/lib/records/hooks";
import { stringifyBody } from "@/lib/records/json";
import type { FieldInput, FormField, ObjectForm, Problems, RecordView } from "@/lib/records/types";
import { ConflictPanel, type Choice } from "./ConflictPanel";
import { FieldEditor, FieldReadValue } from "./FieldRegistry";

const JSON_HEADERS = { "Content-Type": "application/json" };

/** What is held after a save was refused because the record moved: the newest version and what differs. */
interface Moved {
  latest: RecordView;
  conflict: Conflict;
}

/** The long fields take the whole row of a two-column block. */
function wide(field: FormField): boolean {
  return field.type === "LONG_TEXT" || field.type === "MULTI_PICKLIST";
}

/**
 * The create form and the edit form of any object (Sprint 19, ADR-0129): one component, drawn entirely from the
 * description the API gave for this member (`ObjectForm`): the layout decides the blocks and their order, `editable`
 * and `required` decide what can be typed, the record types decide which fields exist. Nothing in here knows an object.
 *
 * <p>What the page does for the person, and what it does not:
 * <ul>
 * <li><strong>Hints</strong> from what a field says about itself (required, length, digits, decimals): they point at a
 * mistake the server would also refuse, in the server's words, and never say a value is fine.</li>
 * <li><strong>Preview</strong>: a moment after a change the form asks the server what a save would be told
 * (`POST .../records/validate`, the real write pipeline run and undone), so a validation rule or a unique value is
 * pointed out beside its field before Save, and a formula shows the result it will have. Nothing is stored.</li>
 * <li><strong>Save</strong> sends only what the person changed (a changed field emptied is cleared). A refusal puts the
 * server's problems beside their fields; a record that moved meanwhile opens the recovery of
 * {@link ConflictPanel} (reload and reapply), never an overwrite.</li>
 * </ul>
 * Every decision is the server's; the page only shows what it answers.
 *
 * @param record the record being edited, or undefined to create one
 * @param recordType the record type the description is for (also what a new record is created with)
 * @param onRecordTypeChange asked when the person picks another record type; the page loads the description again
 */
export function RecordForm({
  objectApiName,
  form,
  record,
  onRecordTypeChange,
  onSaved,
  onCancel,
  onDeleted,
  recordHref,
}: {
  objectApiName: string;
  form: ObjectForm;
  record?: RecordView;
  onRecordTypeChange: (recordType: string) => void;
  onSaved: (saved: RecordView) => void;
  onCancel: () => void;
  onDeleted?: () => void;
  recordHref?: (objectApiName: string, id: string) => string;
}) {
  const [base, setBase] = useState<RecordView | undefined>(record);
  const [edits, setEdits] = useState<Record<string, FieldInput>>({});
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [visited, setVisited] = useState<ReadonlySet<string>>(new Set());
  const [problems, setProblems] = useState<Problems>({});
  const [calculated, setCalculated] = useState<Record<string, unknown>>({});
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | undefined>();
  const [moved, setMoved] = useState<Moved | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const byName = useMemo(() => new Map(form.fields.map((field) => [field.apiName, field])), [form.fields]);
  const initial = useMemo(() => {
    const values: Record<string, FieldInput> = {};
    for (const field of form.fields) {
      values[field.apiName] = base ? inputFromValue(field, recordValue(base, field.apiName)) : defaultInput(field);
    }
    return values;
  }, [form.fields, base]);

  const inputOf = (name: string): FieldInput => edits[name] ?? initial[name] ?? "";
  const changed = useMemo(
    () =>
      Object.keys(edits).filter((name) => {
        const field = byName.get(name);
        return field?.editable && !sameInput(edits[name] as FieldInput, initial[name] ?? "");
      }),
    [edits, initial, byName],
  );
  const typeChanged = base ? form.recordType != null && form.recordType !== (base.recordType ?? null) : false;

  // The labels of the links the record holds (the owner and the lookups), for the read-only fields and the pickers.
  const labelSource = useMemo(() => (base ? [base] : []), [base]);
  const loadedLabels = useLookupLabels(objectApiName, form.fields, labelSource);
  const labelOf = (field: FormField, value: string): string | undefined =>
    chosen[`${field.apiName}|${value}`] ?? loadedLabels[field.apiName]?.[value];

  /** What a save would send: the changed fields set, the emptied ones cleared (a create sends no clears). */
  function bodyOf(): { set: Record<string, unknown>; clear: string[] } {
    const set: Record<string, unknown> = {};
    const clear: string[] = [];
    for (const name of changed) {
      const field = byName.get(name);
      const input = edits[name];
      if (!field || input === undefined) {
        continue;
      }
      if (isEmptyInput(field, input)) {
        if (base) {
          clear.push(name);
        }
      } else {
        set[name] = valueFromInput(field, input);
      }
    }
    return { set, clear };
  }

  // The preview: a moment after a change, ask the server what a save would be told.
  const previewKey = `${changed.join(",")}|${typeChanged}|${base?.version ?? ""}|${form.recordType ?? ""}`;
  const resolving = moved !== undefined;
  useEffect(() => {
    // Not while a save is under way or while the person is choosing how to recover from a conflict.
    if (saving || resolving || (changed.length === 0 && !typeChanged)) {
      return;
    }
    const anyHint = changed.some((name) => {
      const field = byName.get(name);
      return field ? hintFor(field, edits[name] ?? "") !== undefined : false;
    });
    if (anyHint) {
      return;
    }
    const timer = setTimeout(() => {
      const { set, clear } = bodyOf();
      void call(
        () =>
          api.POST("/api/v1/objects/{objectApiName}/records/validate", {
            params: { path: { objectApiName } },
            body: { recordId: base?.id, recordType: base ? (typeChanged ? form.recordType : undefined) : form.recordType, fields: set, clear },
            bodySerializer: stringifyBody,
            headers: JSON_HEADERS,
          }),
      ).then((outcome) => {
        if (!mounted.current) {
          return;
        }
        if (outcome.ok) {
          setProblems({});
          // A formula that would end up empty is previewed as empty (null), which reads as a dash.
          const emptied = Object.fromEntries(outcome.data.data.cleared.map((name) => [name, null]));
          setCalculated({ ...outcome.data.data.calculated, ...emptied });
        } else if (outcome.failure.code === "VALIDATION_ERROR") {
          setProblems(problemsOf(outcome.failure));
        }
      });
    }, 700);
    return () => clearTimeout(timer);
    // The preview follows what was changed; the other values are read when the timer fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewKey, saving, resolving]);

  useEffect(() => {
    if (changed.length === 0) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [changed.length]);

  function edit(field: FormField, input: FieldInput, label?: string) {
    setEdits((current) => ({ ...current, [field.apiName]: input }));
    if (label !== undefined && typeof input === "string" && input !== "") {
      setChosen((current) => ({ ...current, [`${field.apiName}|${input}`]: label }));
    }
    setProblems((current) => {
      if (!(field.apiName in current)) {
        return current;
      }
      const next = { ...current };
      delete next[field.apiName];
      return next;
    });
    setNotice(undefined);
  }

  function problemsFor(field: FormField): string[] {
    const server = problems[field.apiName] ?? [];
    if (server.length > 0) {
      return server;
    }
    const hint = visited.has(field.apiName) && field.editable ? hintFor(field, inputOf(field.apiName)) : undefined;
    return hint ? [hint] : [];
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) {
      return;
    }
    setFailure(undefined);
    setNotice(undefined);
    if (base && changed.length === 0 && !typeChanged) {
      // A save that changes nothing would still raise the record's version and tell everyone it changed.
      setNotice("There is nothing to save: you have not changed anything.");
      return;
    }
    // Hints first, for every field a person can type into: a call that cannot work is not made.
    const found: Problems = {};
    for (const field of form.fields) {
      const hint = field.editable ? hintFor(field, inputOf(field.apiName)) : undefined;
      if (hint) {
        found[field.apiName] = [hint];
      }
    }
    if (Object.keys(found).length > 0) {
      setProblems(found);
      setVisited(new Set(Object.keys(found)));
      const first = Object.keys(found)[0];
      formRef.current?.querySelector<HTMLElement>(`[data-field="${first}"] input, [data-field="${first}"] select, [data-field="${first}"] textarea`)?.focus();
      return;
    }
    setSaving(true);
    const { set, clear } = bodyOf();
    const outcome = base
      ? await call(
          () =>
            api.PATCH("/api/v1/objects/{objectApiName}/records/{id}", {
              params: { path: { objectApiName, id: base.id } },
              body: { version: base.version, fields: set, clear, recordType: typeChanged ? (form.recordType ?? undefined) : undefined },
              bodySerializer: stringifyBody,
              headers: JSON_HEADERS,
            }),
        )
      : await call(
          () =>
            api.POST("/api/v1/objects/{objectApiName}/records", {
              params: { path: { objectApiName } },
              body: { recordType: form.recordType ?? undefined, fields: set },
              bodySerializer: stringifyBody,
              headers: JSON_HEADERS,
            }),
        );
    if (!mounted.current) {
      return;
    }
    if (outcome.ok) {
      setSaving(false);
      onSaved(outcome.data.data);
      return;
    }
    const failed = outcome.failure;
    if (failed.code === "CONCURRENT_MODIFICATION" && base) {
      await loadMoved(base);
    } else if (failed.code === "VALIDATION_ERROR") {
      setProblems(problemsOf(failed));
    } else {
      setFailure(failed.message);
    }
    if (mounted.current) {
      setSaving(false);
    }
  }

  /** The record changed first: read the newest version and compare, so the person can recover without losing work. */
  async function loadMoved(was: RecordView) {
    const latest = await call(() =>
      api.GET("/api/v1/objects/{objectApiName}/records/{id}", { params: { path: { objectApiName, id: was.id } } }),
    );
    if (!mounted.current) {
      return;
    }
    if (!latest.ok) {
      setFailure(
        latest.failure.code === "NOT_FOUND"
          ? "This record was removed, or you can no longer see it, while you were editing it. Nothing was saved."
          : latest.failure.message,
      );
      return;
    }
    const mine: Record<string, FieldInput> = {};
    for (const name of changed) {
      const input = edits[name];
      if (input !== undefined) {
        mine[name] = input;
      }
    }
    setMoved({ latest: latest.data.data, conflict: compareChanges(form.fields, was, latest.data.data, mine) });
  }

  function reapply(choices: Record<string, Choice>) {
    if (!moved) {
      return;
    }
    // The newest version becomes the base; a clash settled for "theirs" drops the person's value for that field.
    setEdits((current) => {
      const next = { ...current };
      for (const [field, choice] of Object.entries(choices)) {
        if (choice === "theirs") {
          delete next[field];
        }
      }
      return next;
    });
    setBase(moved.latest);
    setMoved(undefined);
    setProblems({});
    setCalculated({});
    setNotice("Your changes are now on top of the newest version. Review them, then save again.");
  }

  function discard() {
    if (!moved) {
      return;
    }
    setEdits({});
    setBase(moved.latest);
    setMoved(undefined);
    setProblems({});
    setCalculated({});
    setNotice("Your changes were discarded and the newest version was loaded.");
  }

  async function remove() {
    if (!base) {
      return;
    }
    setSaving(true);
    const outcome = await call(
      () =>
        api.DELETE("/api/v1/objects/{objectApiName}/records/{id}", {
          params: { path: { objectApiName, id: base.id } },
        }),
    );
    if (!mounted.current) {
      return;
    }
    setSaving(false);
    if (outcome.ok) {
      onDeleted?.();
    } else {
      setConfirmingDelete(false);
      setFailure(outcome.failure.message);
    }
  }

  // ---- drawing ----

  const creating = base === undefined;
  const placed = new Set(form.layout.sections.flatMap((section) => section.fields));
  // Problems that are not about a field on this screen (the record as a whole, the record type, a hidden field).
  const loose = Object.entries(problems).flatMap(([place, messages]) => {
    const field = byName.get(place);
    return field && placed.has(place) ? [] : messages.map((message) => (field ? `${field.label}: ${message}` : message));
  });
  const choosable = form.recordTypes.filter((type) => type.selectable || type.apiName === form.recordType);

  return (
    <form ref={formRef} className="record-form" onSubmit={(event) => void submit(event)} noValidate data-testid="record-form">
      {failure ? (
        <p className="form-message" role="alert" data-testid="form-failure">
          {failure}
        </p>
      ) : null}
      {loose.length > 0 ? (
        <div className="form-message" role="alert" data-testid="form-problems">
          {loose.map((message) => (
            <p key={message}>{message}</p>
          ))}
        </div>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
      {moved ? (
        <ConflictPanel
          key={moved.latest.version}
          fields={form.fields}
          conflict={moved.conflict}
          latest={moved.latest}
          labels={loadedLabels}
          busy={saving}
          onReapply={reapply}
          onDiscard={discard}
        />
      ) : null}
      {form.recordTypes.length > 1 ? (
        <div className="field record-type-field">
          <label htmlFor="record-type">Record type</label>
          <select
            id="record-type"
            value={form.recordType ?? ""}
            disabled={choosable.length < 2}
            onChange={(event) => onRecordTypeChange(event.target.value)}
          >
            {choosable.map((type) => (
              <option key={type.apiName} value={type.apiName}>
                {type.label}
              </option>
            ))}
          </select>
        </div>
      ) : null}
      {form.layout.sections.map((section, index) => {
        const names = section.fields.filter((name) => {
          const field = byName.get(name);
          if (!field) {
            return false;
          }
          // A new record shows what can be typed, and a calculated value once the server has worked one out.
          return !creating || field.editable || calculated[name] !== undefined;
        });
        if (names.length === 0) {
          return null;
        }
        return (
          <section key={`${section.label}-${index}`} aria-labelledby={`section-${index}`}>
            <h2 id={`section-${index}`}>{section.label}</h2>
            <div className={`record-grid${section.columns === 2 ? " record-grid-two" : ""}`}>
              {names.map((name) => {
                const field = byName.get(name) as FormField;
                const span = wide(field) || section.columns === 1 ? " span-2" : "";
                if (field.editable) {
                  const input = inputOf(name);
                  return (
                    <div key={name} className={`record-field${span}`}>
                      <FieldEditor
                        objectApiName={objectApiName}
                        field={field}
                        input={input}
                        problems={problemsFor(field)}
                        lookupLabel={typeof input === "string" ? labelOf(field, input) : undefined}
                        onChange={(next, label) => edit(field, next, label)}
                        onBlur={() => setVisited((current) => new Set(current).add(name))}
                      />
                    </div>
                  );
                }
                const value = calculated[name] !== undefined ? calculated[name] : base ? recordValue(base, name) : undefined;
                const text = formatValue(field, value, loadedLabels[name]);
                return (
                  <div key={name} className={`detail-item${span}`} data-field={name}>
                    <div className="dl-label">{field.label}</div>
                    <div className={`dl-value${text === "" ? " muted" : ""}`} data-testid={`value-${name}`}>
                      {text === "" ? (
                        "—"
                      ) : (
                        <FieldReadValue field={field} value={value} labels={loadedLabels[name]} recordHref={recordHref} />
                      )}
                      {calculated[name] !== undefined && field.calculated ? (
                        <span className="hint"> (will be calculated when saved)</span>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
      <div className="form-actions">
        {(creating ? form.actions.create : form.actions.update) ? (
          <button type="submit" className="button" disabled={saving || moved !== undefined} data-testid="save-record">
            {saving ? "Saving…" : creating ? "Create" : "Save"}
          </button>
        ) : (
          <p className="hint">You can look at this record but not change it.</p>
        )}
        <button type="button" className="button button-secondary" onClick={onCancel} disabled={saving}>
          {changed.length > 0 ? "Cancel" : "Close"}
        </button>
        {!creating && form.actions.delete && onDeleted ? (
          confirmingDelete ? (
            <span className="inline-confirm" role="group" aria-label="Confirm delete">
              Move this record to the recycle bin?
              <button type="button" className="button button-danger" onClick={() => void remove()} disabled={saving}>
                Yes, delete
              </button>
              <button type="button" className="link-button" onClick={() => setConfirmingDelete(false)}>
                Keep it
              </button>
            </span>
          ) : (
            <button type="button" className="button button-danger" onClick={() => setConfirmingDelete(true)} disabled={saving}>
              Delete
            </button>
          )
        ) : null}
      </div>
    </form>
  );
}
