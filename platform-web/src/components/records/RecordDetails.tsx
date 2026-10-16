"use client";

import { useMemo } from "react";
import { formatValue, recordValue } from "@/lib/records/fieldValue";
import { useLookupLabels } from "@/lib/records/hooks";
import type { FormField, ObjectForm, RecordView } from "@/lib/records/types";
import { FieldReadValue } from "./FieldRegistry";

/** The long fields take the whole row of a two-column block (the same rule as the form). */
function wide(field: FormField): boolean {
  return field.type === "LONG_TEXT" || field.type === "MULTI_PICKLIST";
}

/**
 * The values of one record laid out the way its form is (Sprint 20, ADR-0131): the same sections, columns and order,
 * every field as text instead of a control. This is what a record page shows until the person chooses Edit, so a
 * record is read first and changed on purpose. It decides nothing: the description (`ObjectForm`) already holds only
 * the fields the member may read, in the layout the page names, and a field with no value reads as a dash.
 */
export function RecordDetails({
  objectApiName,
  form,
  record,
  recordHref,
}: {
  objectApiName: string;
  form: ObjectForm;
  record: RecordView;
  recordHref?: (objectApiName: string, id: string) => string;
}) {
  const byName = useMemo(() => new Map(form.fields.map((field) => [field.apiName, field])), [form.fields]);
  const labelSource = useMemo(() => [record], [record]);
  const labels = useLookupLabels(objectApiName, form.fields, labelSource);

  return (
    <div className="record-form" data-testid="record-details">
      {form.layout.sections.map((section, index) => {
        const names = section.fields.filter((name) => byName.has(name));
        if (names.length === 0) {
          return null;
        }
        return (
          <section key={`${section.label}-${index}`} aria-labelledby={`details-section-${index}`}>
            <h2 id={`details-section-${index}`}>{section.label}</h2>
            <div className={`record-grid${section.columns === 2 ? " record-grid-two" : ""}`}>
              {names.map((name) => {
                const field = byName.get(name) as FormField;
                const span = wide(field) || section.columns === 1 ? " span-2" : "";
                const value = recordValue(record, name);
                const text = formatValue(field, value, labels[name]);
                return (
                  <div key={name} className={`detail-item${span}`} data-field={name}>
                    <div className="dl-label">
                      {field.label}
                      {field.required ? <span aria-hidden="true"> *</span> : null}
                    </div>
                    <div className={`dl-value${text === "" ? " muted" : ""}`} data-testid={`value-${name}`}>
                      {text === "" ? (
                        "—"
                      ) : (
                        <FieldReadValue field={field} value={value} labels={labels[name]} recordHref={recordHref} />
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}
