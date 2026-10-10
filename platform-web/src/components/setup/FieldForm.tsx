"use client";

import { useState, type FormEvent } from "react";
import type { components } from "@/lib/api/generated/schema";
import type { FieldTypeView, FieldView } from "./objectText";

type CreateFieldRequest = components["schemas"]["CreateFieldRequest"];
type UpdateFieldRequest = components["schemas"]["UpdateFieldRequest"];
type FieldSettings = components["schemas"]["FieldSettings"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

interface ValueRow {
  value: string;
  label: string;
  active: boolean;
  /** A value that exists already can be switched off but never removed (the API keeps every value). */
  existing: boolean;
}

interface Draft {
  name: string;
  label: string;
  description: string;
  type: string;
  required: boolean;
  unique: boolean;
  defaultValue: string;
  maxLength: string;
  digits: string;
  precision: string;
  scale: string;
  targetObject: string;
  expression: string;
  resultType: string;
  prefix: string;
  startAt: string;
  width: string;
  values: ValueRow[];
}

const NUMBER_SETTINGS: Array<{ key: "maxLength" | "digits" | "precision" | "scale" | "startAt" | "width"; label: string }> = [
  { key: "maxLength", label: "Maximum length" },
  { key: "digits", label: "Digits" },
  { key: "precision", label: "Digits in all" },
  { key: "scale", label: "Digits after the point" },
  { key: "startAt", label: "First number" },
  { key: "width", label: "Number of digits" },
];

function emptyDraft(type: string): Draft {
  return {
    name: "",
    label: "",
    description: "",
    type,
    required: false,
    unique: false,
    defaultValue: "",
    maxLength: "",
    digits: "",
    precision: "",
    scale: "",
    targetObject: "",
    expression: "",
    resultType: "",
    prefix: "",
    startAt: "",
    width: "",
    values: [],
  };
}

function draftOf(field: FieldView): Draft {
  const s = field.settings;
  return {
    name: field.apiName,
    label: field.label,
    description: field.description,
    type: field.type,
    required: field.required,
    unique: field.unique,
    defaultValue: field.defaultValue ?? "",
    maxLength: s.maxLength?.toString() ?? "",
    digits: s.digits?.toString() ?? "",
    precision: s.precision?.toString() ?? "",
    scale: s.scale?.toString() ?? "",
    targetObject: s.targetObject ?? "",
    expression: s.expression ?? "",
    resultType: s.resultType ?? "",
    prefix: s.prefix ?? "",
    startAt: s.startAt?.toString() ?? "",
    width: s.width?.toString() ?? "",
    values: (s.values ?? []).map((value) => ({ ...value, existing: true })),
  };
}

/** The settings the type takes, from what is typed; empty boxes are left out so the API applies its defaults. */
function settingsOf(spec: FieldTypeView, draft: Draft): FieldSettings {
  const settings: FieldSettings = {};
  for (const { key } of NUMBER_SETTINGS) {
    if (spec.settings.includes(key) && draft[key] !== "") {
      settings[key] = Number(draft[key]);
    }
  }
  if (spec.settings.includes("values")) {
    settings.values = draft.values.map(({ value, label, active }) => ({ value, label, active }));
  }
  if (spec.settings.includes("targetObject")) {
    settings.targetObject = draft.targetObject;
  }
  if (spec.settings.includes("expression")) {
    settings.expression = draft.expression;
  }
  if (spec.settings.includes("resultType")) {
    settings.resultType = draft.resultType;
  }
  if (spec.settings.includes("prefix")) {
    settings.prefix = draft.prefix;
  }
  return settings;
}

/**
 * The form that adds a custom field or changes one. What it offers for a type comes from the field types the API listed
 * (the settings, whether required, unique and a default make sense); every rule is checked by the API, which answers in
 * its own words. The type and the permanent name of an existing field are shown and cannot be changed.
 */
export function FieldForm({
  types,
  objects,
  editing,
  busy,
  onCreate,
  onUpdate,
  onCancel,
}: {
  types: FieldTypeView[];
  objects: ObjectSummary[];
  editing?: FieldView;
  busy: boolean;
  onCreate: (request: CreateFieldRequest) => void;
  onUpdate: (request: UpdateFieldRequest) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<Draft>(() => (editing ? draftOf(editing) : emptyDraft(types[0]?.type ?? "TEXT")));
  const spec = types.find((candidate) => candidate.type === draft.type);
  if (!spec) {
    return null;
  }

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const setRow = (index: number, change: Partial<ValueRow>) =>
    set(
      "values",
      draft.values.map((row, at) => (at === index ? { ...row, ...change } : row)),
    );

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!spec) {
      return;
    }
    const common = {
      label: draft.label,
      description: draft.description,
      required: spec.allowsRequired ? draft.required : false,
      unique: spec.allowsUnique ? draft.unique : false,
      defaultValue: spec.allowsDefault && draft.defaultValue !== "" ? draft.defaultValue : undefined,
      settings: settingsOf(spec, draft),
    };
    if (editing) {
      onUpdate({ ...common, version: editing.version });
    } else {
      onCreate({ ...common, name: draft.name, type: draft.type });
    }
  }

  const activeValues = draft.values.filter((row) => row.active && row.value !== "");

  return (
    <form className="form" onSubmit={submit} noValidate aria-label={editing ? "Change the field" : "Add a field"}>
      {editing ? (
        <p>
          <code>{editing.apiName}</code> · {spec.label}. The name and the type of a field never change.
        </p>
      ) : (
        <>
          <div className="field">
            <label htmlFor="field-name">Name</label>
            <input
              id="field-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => set("name", event.target.value)}
              required
              aria-describedby="field-name-hint"
            />
            <p className="hint" id="field-name-hint">
              The permanent name used by code and integrations, for example salary. The platform adds the ending __c.
            </p>
          </div>
          <div className="field">
            <label htmlFor="field-type">Type</label>
            <select id="field-type" value={draft.type} onChange={(event) => set("type", event.target.value)}>
              {types.map((type) => (
                <option key={type.type} value={type.type}>
                  {type.label}
                </option>
              ))}
            </select>
            <p className="hint">{spec.description}</p>
          </div>
        </>
      )}
      <div className="field">
        <label htmlFor="field-label">Label</label>
        <input
          id="field-label"
          value={draft.label}
          maxLength={80}
          onChange={(event) => set("label", event.target.value)}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="field-description">Description</label>
        <input
          id="field-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => set("description", event.target.value)}
        />
      </div>

      {NUMBER_SETTINGS.filter(({ key }) => spec.settings.includes(key)).map(({ key, label }) => (
        <div className="field" key={key}>
          <label htmlFor={`field-${key}`}>{label}</label>
          <input
            id={`field-${key}`}
            type="number"
            value={draft[key]}
            onChange={(event) => set(key, event.target.value)}
          />
        </div>
      ))}
      {spec.settings.includes("prefix") ? (
        <div className="field">
          <label htmlFor="field-prefix">Prefix</label>
          <input id="field-prefix" value={draft.prefix} maxLength={10} onChange={(event) => set("prefix", event.target.value)} />
        </div>
      ) : null}
      {spec.settings.includes("expression") ? (
        <>
          <div className="field">
            <label htmlFor="field-expression">Formula</label>
            <textarea
              id="field-expression"
              value={draft.expression}
              maxLength={4000}
              onChange={(event) => set("expression", event.target.value)}
              aria-describedby="field-expression-hint"
            />
            <p className="hint" id="field-expression-hint">
              The formula is stored now and worked out in a later release.
            </p>
          </div>
          <div className="field">
            <label htmlFor="field-result">Result</label>
            <select id="field-result" value={draft.resultType} onChange={(event) => set("resultType", event.target.value)}>
              <option value="">Choose…</option>
              {types
                .filter((type) => type.formulaResult)
                .map((type) => (
                  <option key={type.type} value={type.type}>
                    {type.label}
                  </option>
                ))}
            </select>
          </div>
        </>
      ) : null}
      {spec.settings.includes("targetObject") ? (
        <div className="field">
          <label htmlFor="field-target">Points to</label>
          <select
            id="field-target"
            value={draft.targetObject}
            disabled={Boolean(editing)}
            onChange={(event) => set("targetObject", event.target.value)}
          >
            <option value="">Choose an object…</option>
            {objects.map((object) => (
              <option key={object.apiName} value={object.apiName}>
                {object.label} ({object.apiName})
              </option>
            ))}
          </select>
        </div>
      ) : null}
      {spec.settings.includes("values") ? (
        <fieldset className="field">
          <legend>Values</legend>
          {draft.values.map((row, index) => (
            <div key={index} data-testid="value-row">
              <input
                aria-label={`Value ${index + 1}`}
                value={row.value}
                maxLength={80}
                disabled={row.existing}
                onChange={(event) => setRow(index, { value: event.target.value })}
              />{" "}
              <input
                aria-label={`Label of value ${index + 1}`}
                value={row.label}
                maxLength={80}
                onChange={(event) => setRow(index, { label: event.target.value })}
              />{" "}
              <label>
                <input
                  type="checkbox"
                  checked={row.active}
                  onChange={(event) => setRow(index, { active: event.target.checked })}
                />{" "}
                Offered
              </label>{" "}
              {row.existing ? null : (
                <button
                  type="button"
                  className="link-button"
                  onClick={() => set("values", draft.values.filter((_, at) => at !== index))}
                >
                  Remove
                </button>
              )}
            </div>
          ))}
          <button
            type="button"
            className="link-button"
            onClick={() => set("values", [...draft.values, { value: "", label: "", active: true, existing: false }])}
          >
            Add a value
          </button>
          <p className="hint">A value that exists can be switched off, never removed.</p>
        </fieldset>
      ) : null}

      {spec.allowsRequired ? (
        <label>
          <input type="checkbox" checked={draft.required} onChange={(event) => set("required", event.target.checked)} /> Required
        </label>
      ) : null}
      {spec.allowsUnique ? (
        <label>
          <input type="checkbox" checked={draft.unique} onChange={(event) => set("unique", event.target.checked)} /> Unique
        </label>
      ) : null}
      {spec.allowsDefault ? (
        <div className="field">
          <label htmlFor="field-default">Default value</label>
          {draft.type === "BOOLEAN" ? (
            <select id="field-default" value={draft.defaultValue} onChange={(event) => set("defaultValue", event.target.value)}>
              <option value="">None</option>
              <option value="true">Checked</option>
              <option value="false">Not checked</option>
            </select>
          ) : draft.type === "PICKLIST" ? (
            <select id="field-default" value={draft.defaultValue} onChange={(event) => set("defaultValue", event.target.value)}>
              <option value="">None</option>
              {activeValues.map((row) => (
                <option key={row.value} value={row.value}>
                  {row.label || row.value}
                </option>
              ))}
            </select>
          ) : (
            <input id="field-default" value={draft.defaultValue} onChange={(event) => set("defaultValue", event.target.value)} />
          )}
        </div>
      ) : null}

      <button type="submit" className="button" disabled={busy}>
        {editing ? "Save the field" : "Add the field"}
      </button>{" "}
      {editing ? (
        <button type="button" className="link-button" onClick={onCancel}>
          Cancel
        </button>
      ) : null}
    </form>
  );
}
