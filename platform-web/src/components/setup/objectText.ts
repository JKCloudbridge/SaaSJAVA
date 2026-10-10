import type { components } from "@/lib/api/generated/schema";
import { failureFromResponse } from "@/lib/api/errors";

export type FieldView = components["schemas"]["FieldView"];
export type FieldTypeView = components["schemas"]["FieldTypeView"];
export type FieldSettings = components["schemas"]["FieldSettings"];

/** The names of the things a person fills in, for the problems the API lists under the request's own field names. */
const FIELD_NAMES: Record<string, string> = {
  name: "Name",
  label: "Label",
  pluralLabel: "Plural label",
  description: "Description",
  type: "Type",
  required: "Required",
  unique: "Unique",
  defaultValue: "Default value",
  "settings.maxLength": "Maximum length",
  "settings.digits": "Digits",
  "settings.precision": "Digits in all",
  "settings.scale": "Digits after the point",
  "settings.values": "Values",
  "settings.targetObject": "Points to",
  "settings.expression": "Formula",
  "settings.resultType": "Result",
  "settings.prefix": "Prefix",
  "settings.startAt": "First number",
  "settings.width": "Width",
  "settings.onDelete": "When the other record is removed",
  "settings.reparentable": "Moving to another master",
  "settings.listLabel": "List label",
  availableFields: "Available fields",
  picklistSubsets: "Picklist values",
  defaultType: "Default",
  active: "Active",
  problems: "Problem",
  dependencies: "Needed by",
};

/** What happens to a record that points at a removed one, in words, for the two choices of a lookup and the master-detail. */
export const ON_DELETE_TEXT: Record<string, string> = {
  CLEAR: "the link is emptied",
  REFUSE: "the removal is refused",
  CASCADE: "the record is removed too",
};

/** The words for the kinds of change and the actions in the history, from what the API sent. */
export const CHANGE_KIND_TEXT: Record<string, string> = {
  CREATE_OBJECT: "Create object",
  UPDATE_OBJECT: "Change object",
  DELETE_OBJECT: "Remove object",
  CREATE_FIELD: "Add field",
  UPDATE_FIELD: "Change field",
  DELETE_FIELD: "Remove field",
  CREATE_RECORD_TYPE: "Add record type",
  UPDATE_RECORD_TYPE: "Change record type",
  DELETE_RECORD_TYPE: "Remove record type",
};

export const ACTION_TEXT: Record<string, string> = { ADDED: "Added", CHANGED: "Changed", REMOVED: "Removed" };
export const ITEM_TEXT: Record<string, string> = { OBJECT: "object", FIELD: "field", RECORD_TYPE: "record type" };
export const RELEASE_KIND_TEXT: Record<string, string> = {
  QUICK: "A change made at once",
  CHANGE_SET: "A change set",
  ROLLBACK: "A rollback",
};

/**
 * The API's own words for a refusal. A refusal that lists the things that are wrong (a validation error) is shown with
 * each of them, named by what the person filled in; any other refusal is its message. Nothing here decides anything.
 */
export function problemText(error: unknown, response: Response): string {
  const failure = failureFromResponse(error, response);
  const lines = Object.entries(failure.fields ?? {}).flatMap(([field, problems]) =>
    problems.map((problem) => `${FIELD_NAMES[field] ?? field}: ${problem}`),
  );
  return lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message;
}

/** A short description of a field's settings for the table, from what the API sent. */
export function settingsSummary(field: FieldView): string {
  const s = field.settings;
  const parts: string[] = [];
  if (s.maxLength != null) {
    parts.push(`up to ${s.maxLength} characters`);
  }
  if (s.digits != null) {
    parts.push(`up to ${s.digits} digits`);
  }
  if (s.precision != null) {
    parts.push(`${s.precision} digits, ${s.scale ?? 0} after the point`);
  }
  if (s.values) {
    parts.push(`${s.values.length} value${s.values.length === 1 ? "" : "s"}`);
  }
  if (s.targetObject) {
    parts.push(`points to ${s.targetObject}`);
  }
  if (s.onDelete) {
    parts.push(`when it is removed: ${ON_DELETE_TEXT[s.onDelete] ?? s.onDelete}`);
  }
  if (s.reparentable) {
    parts.push("may move to another master");
  }
  if (s.expression) {
    parts.push(`result: ${s.resultType ?? "text"}`);
  }
  if (s.prefix != null && s.startAt != null) {
    parts.push(`${s.prefix || "no prefix"}, from ${s.startAt}, ${s.width ?? 1} digits`);
  }
  return parts.join("; ");
}

export const KIND_TEXT: Record<string, string> = {
  STANDARD: "Standard",
  CUSTOM: "Custom",
  SYSTEM: "System",
};
