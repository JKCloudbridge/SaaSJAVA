"use client";

import { displayType } from "@/lib/records/fieldValue";
import { filterableFields, newFilterRow, OPERATOR_LABELS, type FilterRow, type ListQuery } from "@/lib/records/queryModel";
import type { FormField, ObjectForm } from "@/lib/records/types";
import { LookupPicker } from "./LookupPicker";

/** The comparisons a field offers on this screen: the server's list, without "is one of" for a link (a picker holds one). */
function operatorsOf(field: FormField): string[] {
  return field.type === "LOOKUP" || field.type === "MASTER_DETAIL"
    ? field.filterOperators.filter((op) => op !== "IN")
    : field.filterOperators;
}

/** The control for one value of a condition, by what the field is. */
function ValueInput({
  objectApiName,
  field,
  label,
  value,
  onChange,
  idPrefix,
  chosenLabel,
  onChosen,
}: {
  objectApiName: string;
  field: FormField;
  label: string;
  value: string;
  onChange: (value: string) => void;
  idPrefix: string;
  chosenLabel?: string;
  onChosen: (label: string) => void;
}) {
  const type = displayType(field);
  const common = { id: idPrefix, "aria-label": label };
  if (field.type === "LOOKUP" || field.type === "MASTER_DETAIL") {
    return (
      <LookupPicker
        objectApiName={objectApiName}
        field={field}
        id={idPrefix}
        value={value}
        label={chosenLabel}
        onChange={(identifier, name) => {
          onChange(identifier);
          onChosen(name);
        }}
      />
    );
  }
  if (type === "BOOLEAN") {
    return (
      <select {...common} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Choose…</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    );
  }
  if (type === "PICKLIST") {
    return (
      <select {...common} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Choose…</option>
        {(field.values ?? []).map((choice) => (
          <option key={choice.value} value={choice.value}>
            {choice.label}
          </option>
        ))}
      </select>
    );
  }
  if (type === "DATE") {
    return <input {...common} type="date" value={value} onChange={(event) => onChange(event.target.value)} />;
  }
  if (type === "DATETIME") {
    return <input {...common} type="datetime-local" value={value} onChange={(event) => onChange(event.target.value)} />;
  }
  const numeric = type === "NUMBER" || type === "DECIMAL" || type === "CURRENCY" || type === "PERCENT";
  return (
    <input
      {...common}
      type="text"
      inputMode={numeric ? "decimal" : undefined}
      autoComplete="off"
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

/**
 * Builds the filter of a list (Sprint 19, ADR-0129): a row for each condition, all of them to hold at once. The fields
 * offered and the comparisons for each are the server's list (`filterOperators`), so the screen cannot offer a
 * comparison the query engine would refuse; the values are checked by the server when the list is asked. A condition
 * that is not complete yet is left out of the question, and the question is asked only when the person applies.
 */
export function FilterBuilder({
  objectApiName,
  form,
  draft,
  chosen,
  onChosen,
  onChange,
  onApply,
  onClear,
}: {
  objectApiName: string;
  form: ObjectForm;
  draft: ListQuery;
  chosen: Record<string, string>;
  onChosen: (rowKey: string, label: string) => void;
  onChange: (draft: ListQuery) => void;
  onApply: () => void;
  onClear: () => void;
}) {
  const fields = filterableFields(form);
  const byName = new Map(fields.map((field) => [field.apiName, field]));

  function update(key: string, change: Partial<FilterRow>) {
    onChange({ ...draft, filters: draft.filters.map((row) => (row.key === key ? { ...row, ...change } : row)) });
  }

  function add() {
    const first = fields[0];
    if (first) {
      onChange({ ...draft, filters: [...draft.filters, newFilterRow(first)] });
    }
  }

  return (
    <section aria-labelledby="filters-heading" data-testid="filter-builder">
      <h2 id="filters-heading">Filters</h2>
      {draft.raw ? (
        <p className="hint" data-testid="filter-raw">
          This view uses a filter that cannot be edited here (it combines conditions in a way this screen does not offer).
          It still applies. Clear it to build your own.
        </p>
      ) : (
        <div className="stack">
          {draft.filters.length === 0 ? <p className="hint">No conditions yet. Every record is listed.</p> : null}
          {draft.filters.map((row, index) => {
            const field = byName.get(row.field);
            if (!field) {
              return null;
            }
            const operators = operatorsOf(field);
            const prefix = `filter-${index}`;
            const empty = row.op === "IS_EMPTY" || row.op === "IS_NOT_EMPTY";
            return (
              <div key={row.key} className="filter-row" role="group" aria-label={`Condition ${index + 1}`}>
                <select
                  aria-label="Field"
                  value={row.field}
                  onChange={(event) => {
                    const next = byName.get(event.target.value);
                    if (next) {
                      update(row.key, {
                        field: next.apiName,
                        op: operatorsOf(next)[0] ?? "EQ",
                        text: "",
                        text2: "",
                      });
                    }
                  }}
                >
                  {fields.map((option) => (
                    <option key={option.apiName} value={option.apiName}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <select
                  aria-label="Comparison"
                  value={row.op}
                  onChange={(event) => update(row.key, { op: event.target.value, text: "", text2: "" })}
                >
                  {operators.map((op) => (
                    <option key={op} value={op}>
                      {OPERATOR_LABELS[op] ?? op}
                    </option>
                  ))}
                </select>
                {empty ? null : row.op === "IN" ? (
                  <textarea
                    aria-label="Values, one per line or separated by commas"
                    rows={2}
                    value={row.text}
                    onChange={(event) => update(row.key, { text: event.target.value })}
                  />
                ) : (
                  <ValueInput
                    objectApiName={objectApiName}
                    field={field}
                    label="Value"
                    idPrefix={`${prefix}-value`}
                    value={row.text}
                    chosenLabel={chosen[row.key]}
                    onChosen={(label) => onChosen(row.key, label)}
                    onChange={(value) => update(row.key, { text: value })}
                  />
                )}
                {row.op === "BETWEEN" ? (
                  <ValueInput
                    objectApiName={objectApiName}
                    field={field}
                    label="Second value"
                    idPrefix={`${prefix}-value2`}
                    value={row.text2}
                    onChosen={() => undefined}
                    onChange={(value) => update(row.key, { text2: value })}
                  />
                ) : null}
                <button
                  type="button"
                  className="link-button"
                  onClick={() => onChange({ ...draft, filters: draft.filters.filter((other) => other.key !== row.key) })}
                >
                  Remove
                </button>
              </div>
            );
          })}
          <div>
            <button type="button" className="button button-secondary" onClick={add} disabled={fields.length === 0}>
              Add condition
            </button>
          </div>
        </div>
      )}
      <div className="filters-actions">
        <button type="button" className="button" onClick={onApply}>
          Apply filters
        </button>
        <button type="button" className="button button-secondary" onClick={onClear}>
          Clear filters
        </button>
      </div>
    </section>
  );
}
