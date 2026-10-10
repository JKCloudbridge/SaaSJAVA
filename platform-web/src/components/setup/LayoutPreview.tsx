"use client";

import type { components } from "@/lib/api/generated/schema";
import type { FieldView } from "./objectText";

type PageLayoutDefinition = components["schemas"]["PageLayoutDefinition"];

/** How a field behaves on the page, in words. */
export const BEHAVIOUR_TEXT: Record<string, string> = {
  EDITABLE: "Editable",
  READ_ONLY: "Read only",
  REQUIRED: "Required on this page",
  HIDDEN: "Hidden",
};

/**
 * A made-up value for a field, only to show where it would go: no records exist yet (they arrive with the data engine),
 * so the preview shows the structure of the layout and never a person's data. The word comes from the kind of field,
 * never from anything stored.
 */
export function placeholderOf(field: FieldView | undefined): string {
  if (!field) {
    return "Field not found";
  }
  const first = (field.settings.values ?? []).find((value) => value.active)?.label;
  switch (field.type) {
    case "TEXT":
    case "LONG_TEXT":
      return "Sample text";
    case "NUMBER":
      return "123";
    case "DECIMAL":
    case "CURRENCY":
      return "123.45";
    case "PERCENT":
      return "12%";
    case "BOOLEAN":
      return "Yes";
    case "DATE":
      return "2026-01-31";
    case "DATETIME":
      return "2026-01-31 09:00";
    case "TIME":
      return "09:00";
    case "EMAIL":
      return "name@example.test";
    case "PHONE":
      return "+00 000 0000";
    case "URL":
      return "https://example.test";
    case "PICKLIST":
    case "MULTI_PICKLIST":
      return first ?? "A value";
    case "LOOKUP":
    case "MASTER_DETAIL":
      return "A linked record";
    case "FORMULA":
      return "Calculated";
    case "AUTO_NUMBER":
      return "000123";
    default:
      return "A value";
  }
}

/**
 * The rendering proof of concept for a page layout: given the layout and the object it belongs to, it draws the
 * sections and the fields with sample values, the way a record form will be drawn once records exist. It is a
 * presentation of the structure only. Which fields a person may see or change is decided by the server (field
 * permissions arrive in Sprint 15); this component hides only the fields the layout itself marks as hidden.
 */
export function LayoutPreview({
  layout,
  fields,
  title,
}: {
  layout: PageLayoutDefinition;
  fields: FieldView[];
  title: string;
}) {
  const byName = new Map(fields.map((field) => [field.apiName, field]));
  return (
    <div className="preview" role="group" aria-label={`Preview of ${title}`} data-testid="layout-preview">
      <p className="hint">Preview with sample values. No records exist yet, so nothing here is real data.</p>
      {layout.sections.map((section, index) => {
        const shown = section.fields.filter((entry) => entry.behaviour !== "HIDDEN");
        return (
          <section key={`${section.label}-${index}`} aria-label={section.label} data-testid="preview-section">
            <h5>{section.label}</h5>
            <div className={section.columns === 2 ? "preview-grid preview-grid-two" : "preview-grid"}>
              {shown.map((entry) => {
                const field = byName.get(entry.field);
                const behaviour = entry.behaviour ?? "EDITABLE";
                const id = `preview-${index}-${entry.field}`;
                return (
                  <div className="field" key={entry.field} data-testid="preview-field">
                    <label htmlFor={id}>
                      {field?.label ?? entry.field}
                      {behaviour === "REQUIRED" || field?.required ? <span aria-hidden="true"> *</span> : null}
                    </label>
                    <input
                      id={id}
                      value={placeholderOf(field)}
                      readOnly
                      disabled={behaviour === "READ_ONLY"}
                      aria-required={behaviour === "REQUIRED" || field?.required ? true : undefined}
                    />
                    {behaviour === "READ_ONLY" ? <p className="hint">{BEHAVIOUR_TEXT.READ_ONLY}</p> : null}
                  </div>
                );
              })}
            </div>
            {shown.length < section.fields.length ? (
              <p className="hint">{section.fields.length - shown.length} hidden field(s) are kept in the layout.</p>
            ) : null}
          </section>
        );
      })}
    </div>
  );
}
