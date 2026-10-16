import { inputFromValue, recordValue } from "./fieldValue";
import type { FieldInput, ObjectForm, RecordView } from "./types";

/**
 * The fields of a record that a copy of it starts with (Sprint 20, ADR-0131): what a person could type into the new
 * record, minus what must differ between two records. A unique field or an identifier from another system would be
 * refused as already in use, the owner of a copy is whoever makes it, and a system or calculated field is never typed.
 * Only a convenience: the server still judges the copy as it judges any new record.
 */
export function cloneableInputs(form: ObjectForm, template: RecordView): Record<string, FieldInput> {
  const inputs: Record<string, FieldInput> = {};
  for (const field of form.fields) {
    const copies = field.editable && !field.unique && !field.externalId && field.apiName !== "ownerId";
    if (copies) {
      inputs[field.apiName] = inputFromValue(field, recordValue(template, field.apiName));
    }
  }
  return inputs;
}
