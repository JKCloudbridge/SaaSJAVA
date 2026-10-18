"use client";

import { useEffect, useRef, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type Person = components["schemas"]["ApprovalPersonView"];

/**
 * Chooses a person who can be asked to decide (Sprint 24, ADR-0146). The person types part of a name and picks from what the
 * API offers: active members who hold a licence, a few at a time, found by a part of their name. Nothing is listed without
 * typing something that matches, and the server judges the choice again when it is used (a delegate or a new approver must
 * still be an active member with a licence), so this offers, it never permits.
 */
export function PersonPicker({
  id,
  label,
  value,
  name,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  /** The identifier of the person chosen, or "". */
  value: string;
  /** The name of the person chosen, when it is known. */
  name?: string;
  onChange: (identifier: string, name: string) => void;
  disabled?: boolean;
}) {
  const [text, setText] = useState("");
  const [options, setOptions] = useState<Person[] | undefined>();
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
    clearTimeout(timer.current);
    if (typed.trim() === "") {
      setOptions(undefined);
      setMessage(undefined);
      return;
    }
    timer.current = setTimeout(async () => {
      try {
        const { data, error, response } = await api.GET("/api/v1/approvals/people", {
          params: { query: { text: typed.trim(), limit: 10 } },
        });
        if (!mounted.current || mine !== latest.current) {
          return;
        }
        if (data) {
          setOptions(data.data);
          setMessage(undefined);
        } else {
          setOptions(undefined);
          setMessage(await failureText(error, response));
        }
      } catch {
        if (mounted.current && mine === latest.current) {
          setMessage(COMMON_TEXT.network);
        }
      }
    }, 250);
  }

  return (
    <div className="field" data-testid={`${id}-picker`}>
      <label htmlFor={id}>{label}</label>
      {value !== "" ? (
        <p>
          <span className="badge" data-testid={`${id}-chosen`}>
            {name && name !== "" ? name : value}
          </span>{" "}
          <button
            type="button"
            className="link-button"
            disabled={disabled}
            onClick={() => {
              onChange("", "");
              setText("");
              setOptions(undefined);
            }}
          >
            Choose someone else
          </button>
        </p>
      ) : (
        <>
          <input
            id={id}
            type="search"
            value={text}
            disabled={disabled}
            autoComplete="off"
            placeholder="Type part of a name"
            onChange={(event) => {
              setText(event.target.value);
              search(event.target.value);
            }}
          />
          {message ? (
            <p className="form-message" role="alert">
              {message}
            </p>
          ) : null}
          {options && options.length === 0 ? <p className="hint">Nobody who can be asked matches.</p> : null}
          {options && options.length > 0 ? (
            <ul className="picker-options" aria-label={`${label}: people found`}>
              {options.map((one) => (
                <li key={one.userId}>
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => {
                      onChange(one.userId, one.name ?? one.userId);
                      setOptions(undefined);
                      setText("");
                    }}
                  >
                    {one.name ?? one.userId}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </>
      )}
    </div>
  );
}
