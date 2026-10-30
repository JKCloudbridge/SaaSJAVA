"use client";

import type { ReactNode } from "react";
import type { FormSlotAccess } from "@/components/records/RecordForm";
import type { NumberingInfo } from "./api";
import { digitsOnly, normalizePart, padSerial } from "./numbering";

/**
 * The fields of the template form that build its number on the screen (Functional track FA11B, ADR-0206): Part A and
 * Part B typed freehand (capitals, digits, hyphen), and the Document number that grows as they are typed, ending in the
 * serial box. The serial is typed only for a combination that has no numbers yet; otherwise the box is disabled and
 * shows the next number. The numbering-info answer decides all of it, and the Save decides again.
 */

/** A part typed freehand: capital letters, digits and a hyphen, corrected as it is typed. */
export function PartField({ name, label, access, hint }: { name: string; label: string; access: FormSlotAccess; hint?: string }) {
  const problems = access.problems(name);
  const editable = access.editable(name);
  const id = `part-${name}`;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type="text"
        value={access.value(name)}
        disabled={!editable}
        maxLength={10}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={problems.length > 0 ? true : undefined}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(event) => access.set(name, normalizePart(event.target.value))}
      />
      {hint ? (
        <p id={`${id}-hint`} className="hint">
          {hint}
        </p>
      ) : null}
      {problems.map((message) => (
        <p key={message} className="field-error" role="alert">
          {message}
        </p>
      ))}
    </div>
  );
}

/** The Document number as it builds itself, with the serial box at its end when the number has a serial. */
export function NumberField({
  access,
  info,
  savedNumber,
}: {
  access: FormSlotAccess;
  info: NumberingInfo | undefined;
  /** The number the template already has when what is typed still means the same identity; shown as it is. */
  savedNumber: string | undefined;
}) {
  const problems = [...access.problems("serial"), ...access.problems("number")];
  const body = ((): ReactNode => {
    if (savedNumber !== undefined) {
      return <span className="live-number-text">{savedNumber}</span>;
    }
    if (!info?.known) {
      return <span className="muted">—</span>;
    }
    if (!info.serial) {
      return (
        <span className="live-number-text">
          {info.number ?? info.prefix}
          {info.complete ? "" : "…"}
        </span>
      );
    }
    const typable = access.editable("serial") && info.canStart;
    if (!info.complete) {
      return <span className="live-number-text">{info.prefix}…</span>;
    }
    if (info.started) {
      return (
        <>
          <span className="live-number-text">{info.prefix}</span>
          <input
            type="text"
            className="serial-input"
            aria-label="Serial number (automatic)"
            data-testid="serial-input"
            value={info.nextSerial == null ? "" : padSerial(info.nextSerial, info.width)}
            placeholder="auto"
            disabled
            readOnly
          />
        </>
      );
    }
    if (typable) {
      return (
        <>
          <span className="live-number-text">{info.prefix}</span>
          <input
            type="text"
            inputMode="numeric"
            className="serial-input"
            aria-label="Serial number"
            data-testid="serial-input"
            value={access.value("serial")}
            placeholder={padSerial(1, info.width)}
            maxLength={12}
            autoComplete="off"
            aria-invalid={problems.length > 0 ? true : undefined}
            onChange={(event) => access.set("serial", digitsOnly(event.target.value))}
          />
        </>
      );
    }
    return <span className="live-number-text">{info.prefix}…</span>;
  })();

  const note = ((): string | undefined => {
    if (savedNumber !== undefined || !info?.known || !info.serial || !info.complete) {
      return undefined;
    }
    if (info.started) {
      return "This combination already has numbers; the next one is given automatically.";
    }
    return access.editable("serial") && info.canStart
      ? undefined
      : "The first number of this combination is set when the template is approved.";
  })();

  return (
    <div className="field">
      <div className="field-label" id="live-number-label">
        Document number
      </div>
      <div className="live-number" role="group" aria-labelledby="live-number-label" data-testid="live-number">
        {body}
      </div>
      {note ? <p className="hint">{note}</p> : null}
      {problems.map((message) => (
        <p key={message} className="field-error" role="alert">
          {message}
        </p>
      ))}
    </div>
  );
}
