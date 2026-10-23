"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { controlOf, formatValue, hrefOfValue, type Control } from "@/lib/records/fieldValue";
import type { FieldInput, FormField } from "@/lib/records/types";
import { LookupPicker } from "./LookupPicker";

/**
 * The field component registry (Sprint 19, ADR-0129): for each of the 19 field types, how a value is read (a list cell,
 * a read-only field of a form) and how it is edited. The types are the platform's (Sprint 10), the table below is the one
 * place a type is mapped to a control, and `fieldValue.ts` holds the data side (what a control holds, what is shown).
 * Both come from what the API says about the field; nothing here decides whether a field may be edited: a field arrives
 * with `editable` already worked out by the server from the member's permissions, the record type and the layout.
 *
 * <p>How each type reads and edits (a list cell reads the same as a read-only field, only cut short):
 * <ul>
 * <li>text, e-mail, phone, URL: the text; e-mail, phone and a web address (http or https only) are links. Edited as a
 * line of text with the matching control.</li>
 * <li>long text: the text, cut short in a cell; a text area.</li>
 * <li>number, decimal, currency, percent: grouped digits (currency with its decimals, percent with its sign); edited as
 * text with a numeric keyboard, so the digits typed are the digits sent.</li>
 * <li>checkbox: Yes / No; a checkbox.</li>
 * <li>date, date and time, time: the reader's date and time; the matching native control (a date and time in local
 * time, stored in UTC).</li>
 * <li>picklist, multi-select picklist: the labels of the values; a list to choose from (the choices the record type
 * allows) or checkboxes.</li>
 * <li>lookup, master-detail: the name of the record, a link to it when it can be opened; a search to pick one.</li>
 * <li>formula, auto-number: read only everywhere (a formula as its result type); the form previews a formula's result
 * after the server has judged the save.</li>
 * </ul>
 */

/** The 19 field types, so a new one is an explicit decision (`fieldValue.test.ts` fails until `controlOf` knows it). */
export const FIELD_TYPES = [
  "TEXT",
  "LONG_TEXT",
  "NUMBER",
  "DECIMAL",
  "CURRENCY",
  "PERCENT",
  "BOOLEAN",
  "DATE",
  "DATETIME",
  "TIME",
  "EMAIL",
  "PHONE",
  "URL",
  "PICKLIST",
  "MULTI_PICKLIST",
  "LOOKUP",
  "MASTER_DETAIL",
  "FORMULA",
  "AUTO_NUMBER",
] as const;

// ---- reading ----

/**
 * A value as it reads. A link is a link only for a mail address, a telephone number, a web address (http or https) and
 * a record that can be opened; everything else is text. An empty value reads as nothing.
 *
 * @param recordHref where a linked record of an object that can be opened is, by object and identifier
 */
export function FieldReadValue({
  field,
  value,
  labels = {},
  recordHref,
}: {
  field: FormField;
  value: unknown;
  labels?: Record<string, string>;
  recordHref?: (objectApiName: string, id: string) => string;
}): ReactNode {
  const text = formatValue(field, value, labels);
  if (text === "") {
    return null;
  }
  const href = hrefOfValue(field, value);
  if (href) {
    const outside = href.startsWith("http");
    return (
      <a href={href} {...(outside ? { target: "_blank", rel: "noopener noreferrer" } : {})}>
        {text}
      </a>
    );
  }
  if ((field.type === "LOOKUP" || field.type === "MASTER_DETAIL") && field.targetOpenable && field.targetObject && recordHref) {
    const label = labels[String(value)];
    return label !== undefined ? <Link href={recordHref(field.targetObject, String(value))}>{text}</Link> : text;
  }
  return text;
}

// ---- editing ----

interface EditorProps {
  objectApiName: string;
  field: FormField;
  id: string;
  input: FieldInput;
  onChange: (input: FieldInput, label?: string) => void;
  onBlur: () => void;
  invalid: boolean;
  describedBy?: string;
  /** For a link: the name of the record it points to. */
  lookupLabel?: string;
}

function textInput(type: string, props: EditorProps, extra: Record<string, string> = {}): ReactNode {
  return (
    <input
      id={props.id}
      type={type}
      value={String(props.input)}
      aria-invalid={props.invalid ? true : undefined}
      aria-describedby={props.describedBy}
      onChange={(event) => props.onChange(event.target.value)}
      onBlur={props.onBlur}
      {...extra}
    />
  );
}

/** One editor per control; the table is the registry's edit side. */
const EDITORS: Record<Control, (props: EditorProps) => ReactNode> = {
  text: (props) => textInput("text", props),
  textarea: (props) => (
    <textarea
      id={props.id}
      rows={4}
      value={String(props.input)}
      aria-invalid={props.invalid ? true : undefined}
      aria-describedby={props.describedBy}
      onChange={(event) => props.onChange(event.target.value)}
      onBlur={props.onBlur}
    />
  ),
  // Text with a numeric keyboard, not type=number: the digits typed are the digits sent (no rounding, no locale).
  number: (props) => textInput("text", props, { inputMode: "decimal", autoComplete: "off" }),
  checkbox: (props) => (
    <input
      id={props.id}
      type="checkbox"
      checked={props.input === true}
      aria-describedby={props.describedBy}
      onChange={(event) => props.onChange(event.target.checked)}
      onBlur={props.onBlur}
    />
  ),
  date: (props) => textInput("date", props),
  datetime: (props) => textInput("datetime-local", props, { step: /:\d{2}:\d{2}$/.test(String(props.input)) ? "1" : "60" }),
  time: (props) => textInput("time", props, { step: /:\d{2}:\d{2}$/.test(String(props.input)) ? "1" : "60" }),
  email: (props) => textInput("email", props, { autoComplete: "off" }),
  tel: (props) => textInput("tel", props, { autoComplete: "off" }),
  url: (props) => textInput("url", props, { autoComplete: "off" }),
  select: (props) => {
    const held = String(props.input);
    // A value the list no longer offers is still shown while the record holds it, so it is not lost by looking.
    const choices = (props.field.values ?? []).filter((choice) => choice.active || choice.value === held);
    return (
      <select
        id={props.id}
        value={held}
        aria-invalid={props.invalid ? true : undefined}
        aria-describedby={props.describedBy}
        onChange={(event) => props.onChange(event.target.value)}
        onBlur={props.onBlur}
      >
        <option value="">{props.field.required ? "Choose…" : "None"}</option>
        {choices.map((choice) => (
          <option key={choice.value} value={choice.value}>
            {choice.label}
          </option>
        ))}
      </select>
    );
  },
  multiselect: (props) => {
    const held = Array.isArray(props.input) ? props.input : [];
    const choices = (props.field.values ?? []).filter((choice) => choice.active || held.includes(choice.value));
    return (
      <div className="check-list" role="group" aria-labelledby={`${props.id}-label`} aria-describedby={props.describedBy}>
        {choices.map((choice) => (
          <label key={choice.value}>
            <input
              type="checkbox"
              checked={held.includes(choice.value)}
              onChange={(event) =>
                props.onChange(
                  event.target.checked ? [...held, choice.value] : held.filter((value) => value !== choice.value),
                )
              }
              onBlur={props.onBlur}
            />
            {choice.label}
          </label>
        ))}
      </div>
    );
  },
  lookup: (props) => (
    <LookupPicker
      objectApiName={props.objectApiName}
      field={props.field}
      id={props.id}
      value={String(props.input)}
      label={props.lookupLabel}
      invalid={props.invalid}
      describedBy={props.describedBy}
      onChange={(identifier, label) => props.onChange(identifier, label)}
    />
  ),
  none: () => null,
};

/**
 * A field of a form that a person can type into: its label (with a mark when a value is required), the control for its
 * type, the problems found with it and the preview of what the server will calculate. The marks are the server's
 * (`required` is the field's or the layout's); `problems` are the server's words or the hints of `fieldValue.hintFor`.
 */
export function FieldEditor({
  objectApiName,
  field,
  input,
  onChange,
  onBlur,
  problems,
  lookupLabel,
}: {
  objectApiName: string;
  field: FormField;
  input: FieldInput;
  onChange: (input: FieldInput, label?: string) => void;
  onBlur: () => void;
  problems: readonly string[];
  lookupLabel?: string;
}) {
  const id = `field-${field.apiName}`;
  const control = controlOf(field);
  const errorId = `${id}-error`;
  // The field's own description is for the setup screens: a form never explains itself under a field.
  const describedBy = problems.length > 0 ? errorId : undefined;
  const editor = EDITORS[control]({
    objectApiName,
    field,
    id,
    input,
    onChange,
    onBlur,
    invalid: problems.length > 0,
    describedBy,
    lookupLabel,
  });
  const mark = field.required ? (
    <span aria-hidden="true" className="required-mark">
      {" "}
      *
    </span>
  ) : null;
  const text = (
    <>
      {field.label}
      {mark}
      {field.required ? <span className="visually-hidden"> (required)</span> : null}
    </>
  );
  return (
    <div className="field" data-field={field.apiName}>
      {control === "checkbox" ? (
        <label htmlFor={id}>
          {editor}
          {text}
        </label>
      ) : control === "multiselect" ? (
        <>
          <span className="field-label" id={`${id}-label`}>
            {text}
          </span>
          {editor}
        </>
      ) : (
        <>
          <label htmlFor={id}>{text}</label>
          {editor}
        </>
      )}
      {problems.length > 0 ? (
        <p className="field-error" id={errorId} role="alert">
          {problems.join(" ")}
        </p>
      ) : null}
    </div>
  );
}
