import { DecimalText, plainNumber } from "./json";
import type { FieldInput, FormField, RecordValues, RecordView } from "./types";

/**
 * The data side of the field component registry (Sprint 19, ADR-0129): for each of the 19 field types, how a stored value
 * becomes what a control holds, how what a person typed becomes what the API accepts, what is shown for a value, and
 * what can be pointed out before a save is tried.
 *
 * <p>Everything here is presentation. The server judges every value again (shape, type, required, rules, formulas);
 * {@link hintFor} only keeps a person from sending a value that cannot be right, and it never says a value is
 * acceptable. The words of a hint are the server's words for the same mistake, so a hint and a refusal read alike.
 */

/** The control a field type is edited with. */
export type Control =
  | "text"
  | "textarea"
  | "number"
  | "checkbox"
  | "date"
  | "datetime"
  | "time"
  | "email"
  | "tel"
  | "url"
  | "select"
  | "multiselect"
  | "lookup"
  | "none";

/** The type a field is shown and edited as: a formula is shown as its result. */
export function displayType(field: FormField): string {
  return field.type === "FORMULA" ? (field.resultType ?? "TEXT") : field.type;
}

/** The control for a field; a calculated field and a field this member may not type into have none. */
export function controlOf(field: FormField): Control {
  if (field.calculated) {
    return "none";
  }
  switch (field.type) {
    case "LONG_TEXT":
      return "textarea";
    case "NUMBER":
    case "DECIMAL":
    case "CURRENCY":
    case "PERCENT":
      return "number";
    case "BOOLEAN":
      return "checkbox";
    case "DATE":
      return "date";
    case "DATETIME":
      return "datetime";
    case "TIME":
      return "time";
    case "EMAIL":
      return "email";
    case "PHONE":
      return "tel";
    case "URL":
      return "url";
    case "PICKLIST":
      return "select";
    case "MULTI_PICKLIST":
      return "multiselect";
    case "LOOKUP":
    case "MASTER_DETAIL":
      return "lookup";
    default:
      return "text";
  }
}

/** The value a record holds for a field: the system fields that sit beside the values come from the record itself. */
export function recordValue(record: Pick<RecordView, "id" | "ownerId" | "createdAt" | "updatedAt" | "fields">, apiName: string): unknown {
  switch (apiName) {
    case "id":
      return record.id;
    case "ownerId":
      return record.ownerId;
    case "createdAt":
      return record.createdAt;
    case "updatedAt":
      return record.updatedAt;
    default:
      return record.fields[apiName];
  }
}

// ---- value <-> what a control holds ----

const WHOLE = /^-?\d+$/;
const DECIMAL = /^-?\d+(\.\d+)?$/;

/** What a control holds when the field has no value. */
export function emptyInput(field: FormField): FieldInput {
  switch (controlOf(field)) {
    case "checkbox":
      return false;
    case "multiselect":
      return [];
    default:
      return "";
  }
}

/**
 * What a control holds for the default a field says a new record gets (the default is written as text). The server
 * applies the same default when the field is left out, so a control that still holds it is not sent.
 */
export function defaultInput(field: FormField): FieldInput {
  const text = field.defaultValue;
  if (text === undefined || text === null) {
    return emptyInput(field);
  }
  switch (controlOf(field)) {
    case "checkbox":
      return text === "true";
    case "multiselect":
      return text.split(";").filter((part) => part !== "");
    case "datetime":
      return localInputOfInstant(text);
    default:
      return text;
  }
}

/** Whether a control holds no value. A checkbox always holds one (unchecked is a value). */
export function isEmptyInput(field: FormField, input: FieldInput): boolean {
  if (typeof input === "boolean") {
    return false;
  }
  if (Array.isArray(input)) {
    return input.length === 0;
  }
  return input.trim() === "";
}

/** The two forms of a date and time: the stored UTC instant and the value of a `datetime-local` control. */
function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** The `datetime-local` value (local time) for a stored instant, with seconds only when there are some. */
export function localInputOfInstant(instant: string): string {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const day = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  return date.getSeconds() === 0 ? `${day}T${time}` : `${day}T${time}:${pad(date.getSeconds())}`;
}

/** The instant (UTC, as the API wants it) for a `datetime-local` value, or null when it is not a date and time. */
export function instantOfLocalInput(local: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(local)) {
    return null;
  }
  const date = new Date(local);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** What a control holds for a stored value. */
export function inputFromValue(field: FormField, value: unknown): FieldInput {
  if (value === undefined || value === null) {
    return emptyInput(field);
  }
  switch (controlOf(field)) {
    case "checkbox":
      return value === true;
    case "multiselect":
      return Array.isArray(value) ? value.map((item) => String(item)) : [];
    case "number":
      return typeof value === "number" ? plainNumber(value) : String(value);
    case "datetime":
      return localInputOfInstant(String(value));
    default:
      return String(value);
  }
}

/**
 * What the API accepts for what a control holds. Only for a value that is not empty and has no hint: a decimal is kept
 * as the digits that were typed, a date and time becomes the instant it names.
 */
export function valueFromInput(field: FormField, input: FieldInput): unknown {
  switch (controlOf(field)) {
    case "checkbox":
      return input === true;
    case "multiselect":
      return Array.isArray(input) ? input : [];
    case "number":
      return new DecimalText(String(input).trim());
    case "datetime":
      return instantOfLocalInput(String(input)) ?? String(input);
    case "date":
    case "time":
    case "email":
    case "tel":
    case "url":
    case "select":
    case "lookup":
      return String(input).trim();
    default:
      return String(input);
  }
}

/** Whether two things a control can hold are the same, for "did the person change this field". */
export function sameInput(a: FieldInput, b: FieldInput): boolean {
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((item, index) => item === b[index]);
  }
  return a === b;
}

// ---- hints, before a save is tried ----

/**
 * A reason a value cannot be right, from what the field says about itself; undefined when nothing is wrong that can be
 * seen without the server. A field that is empty is a hint only when it is required (and the person can type into it).
 */
export function hintFor(field: FormField, input: FieldInput): string | undefined {
  if (isEmptyInput(field, input)) {
    return field.required && field.editable ? "Enter a value for this field." : undefined;
  }
  const text = typeof input === "string" ? input.trim() : "";
  switch (field.type) {
    case "TEXT":
    case "LONG_TEXT":
    case "EMAIL":
    case "PHONE":
    case "URL":
      return field.maxLength != null && typeof input === "string" && input.length > field.maxLength
        ? `Is longer than the ${field.maxLength} characters this field allows.`
        : undefined;
    case "NUMBER": {
      if (!WHOLE.test(text)) {
        return "Enter a whole number.";
      }
      const digits = text.replace("-", "").replace(/^0+(?=\d)/, "").length;
      return field.digits != null && digits > field.digits
        ? `Has more than the ${field.digits} digits this field allows.`
        : undefined;
    }
    case "DECIMAL":
    case "CURRENCY":
    case "PERCENT": {
      if (!DECIMAL.test(text)) {
        return "Enter a number.";
      }
      const [whole = "", fraction = ""] = text.replace("-", "").split(".");
      const trimmedFraction = fraction.replace(/0+$/, "");
      if (field.scale != null && trimmedFraction.length > field.scale) {
        return `Has more than the ${field.scale} decimals this field allows.`;
      }
      if (field.precision != null && field.scale != null) {
        const wholeDigits = Math.max(field.precision - field.scale, 1);
        if (whole.replace(/^0+(?=\d)/, "").length > wholeDigits && Number(whole) !== 0) {
          return `Has more than the ${field.precision - field.scale} digits before the decimal point this field allows.`;
        }
      }
      return undefined;
    }
    case "DATETIME":
      return instantOfLocalInput(text) === null ? "Enter a date and time." : undefined;
    default:
      return undefined;
  }
}

// ---- what is shown ----

const NUMBER_STYLE: Intl.NumberFormatOptions = { maximumFractionDigits: 20 };

function formatNumber(value: unknown, options: Intl.NumberFormatOptions = NUMBER_STYLE): string {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number.toLocaleString(undefined, options) : String(value);
}

/** A stored day (year-month-day) as the reader's date, without a time zone shifting it. */
export function formatDay(day: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!match) {
    return day;
  }
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return date.toLocaleDateString(undefined, { timeZone: "UTC" });
}

/** A stored instant as the reader's date and time. */
export function formatInstant(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : date.toLocaleString();
}

/** The label of a picklist value; a value the list no longer has is shown as it was stored. */
export function picklistLabel(field: FormField, value: string): string {
  return field.values?.find((choice) => choice.value === value)?.label ?? value;
}

/**
 * The words for a value, for a list cell and a read-only field. A link is named from `labels` (the identifiers the
 * screen asked the API to name); one that could not be named reads "Linked record", never the identifier.
 */
export function formatValue(field: FormField, value: unknown, labels: Record<string, string> = {}): string {
  if (value === undefined || value === null || value === "") {
    return "";
  }
  switch (displayType(field)) {
    case "BOOLEAN":
      return value === true ? "Yes" : "No";
    case "NUMBER":
      return formatNumber(value, { maximumFractionDigits: 0 });
    case "DECIMAL":
      return formatNumber(value, { maximumFractionDigits: field.scale ?? 20 });
    case "CURRENCY": {
      const places = field.scale ?? 2;
      return formatNumber(value, { minimumFractionDigits: places, maximumFractionDigits: places });
    }
    case "PERCENT":
      return `${formatNumber(value, { maximumFractionDigits: field.scale ?? 20 })}%`;
    case "DATE":
      return formatDay(String(value));
    case "DATETIME":
      return formatInstant(String(value));
    case "TIME":
      return String(value).slice(0, 8);
    case "PICKLIST":
      return picklistLabel(field, String(value));
    case "MULTI_PICKLIST":
      return Array.isArray(value) ? value.map((item) => picklistLabel(field, String(item))).join(", ") : "";
    case "LOOKUP":
    case "MASTER_DETAIL":
      return labels[String(value)] ?? "Linked record";
    default:
      return String(value);
  }
}

/** Where a value leads when it can be followed: a mail address, a telephone number, a web address (http or https only). */
export function hrefOfValue(field: FormField, value: unknown): string | undefined {
  if (typeof value !== "string" || value === "") {
    return undefined;
  }
  switch (displayType(field)) {
    case "EMAIL":
      return `mailto:${value}`;
    case "PHONE":
      return `tel:${value.replace(/[^\d+]/g, "")}`;
    case "URL":
      return /^https?:\/\//i.test(value) ? value : undefined;
    default:
      return undefined;
  }
}

/** The identifiers a record holds in its link fields, by field, for asking the API to name them. */
export function linkIdentifiers(fields: readonly FormField[], records: readonly { id: string; ownerId: string; fields: RecordValues }[]): Record<string, string[]> {
  const found: Record<string, Set<string>> = {};
  for (const field of fields) {
    if (field.type !== "LOOKUP" && field.type !== "MASTER_DETAIL") {
      continue;
    }
    for (const record of records) {
      const value = recordValue({ ...record, createdAt: "", updatedAt: "" }, field.apiName);
      if (typeof value === "string" && value !== "") {
        (found[field.apiName] ??= new Set()).add(value);
      }
    }
  }
  return Object.fromEntries(Object.entries(found).map(([name, ids]) => [name, [...ids]]));
}
