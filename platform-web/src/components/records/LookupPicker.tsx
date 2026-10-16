"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import type { FormField } from "@/lib/records/types";

interface Option {
  id: string;
  label: string;
}

/**
 * Chooses the record a link field points to (Sprint 19, ADR-0129). The person types part of a name and picks from what
 * the API offers: records of the target object they may read (a person is offered while their membership is active),
 * a few at a time, found by the same search a list uses. Nothing is listed without typing something that matches more
 * than the first few, and the write that follows is judged again by the server (a link is accepted only to a record the
 * caller can read), so this offers, it never permits.
 */
export function LookupPicker({
  objectApiName,
  field,
  id,
  value,
  label,
  onChange,
  disabled,
  invalid,
  describedBy,
}: {
  objectApiName: string;
  field: FormField;
  id: string;
  /** The identifier the link holds, or "". */
  value: string;
  /** The name of the record it points to, when it is known. */
  label?: string;
  onChange: (identifier: string, label: string) => void;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
}) {
  const [text, setText] = useState("");
  const [options, setOptions] = useState<Option[] | undefined>();
  const [message, setMessage] = useState<string | undefined>();
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const latest = useRef(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      clearTimeout(timer.current);
    };
  }, []);

  function search(typed: string) {
    latest.current += 1;
    const mine = latest.current;
    void call(() =>
      api.POST("/api/v1/objects/{objectApiName}/lookups/{fieldApiName}/options", {
        params: { path: { objectApiName, fieldApiName: field.apiName } },
        // The text is part of a name: it goes in the body, never in the address.
        body: { text: typed, limit: 10 },
      }),
    ).then((outcome) => {
      // Only the newest question counts: an older, slower answer must not replace a newer one.
      if (!mounted.current || mine !== latest.current) {
        return;
      }
      if (outcome.ok) {
        setOptions(outcome.data.data);
        setMessage(undefined);
      } else {
        setOptions(undefined);
        setMessage(outcome.failure.message);
      }
    });
  }

  function type(next: string) {
    setText(next);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => search(next), 250);
  }

  if (value !== "") {
    return (
      <span className="lookup-chosen" id={id} data-testid={`lookup-${field.apiName}`}>
        <span className="lookup-chip">{label ?? "Linked record"}</span>
        {disabled ? null : (
          <button
            type="button"
            className="link-button"
            onClick={() => {
              onChange("", "");
              setText("");
              setOptions(undefined);
            }}
          >
            Change
          </button>
        )}
      </span>
    );
  }

  return (
    <div className="lookup" data-testid={`lookup-${field.apiName}`}>
      <input
        id={id}
        type="text"
        autoComplete="off"
        value={text}
        disabled={disabled}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy}
        placeholder={`Search ${field.targetLabel ?? "records"}`}
        onFocus={() => (options === undefined ? search(text) : undefined)}
        onChange={(event) => type(event.target.value)}
      />
      {message ? (
        <p className="field-error" role="alert">
          {message}
        </p>
      ) : null}
      {options && options.length === 0 ? <p className="hint">Nothing matches.</p> : null}
      {options && options.length > 0 ? (
        <ul className="lookup-options" aria-label={`Matching ${field.targetLabel ?? "records"}`}>
          {options.map((option) => (
            <li key={option.id}>
              <button
                type="button"
                className="lookup-option"
                onClick={() => {
                  onChange(option.id, option.label);
                  setText("");
                  setOptions(undefined);
                }}
              >
                {option.label}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
