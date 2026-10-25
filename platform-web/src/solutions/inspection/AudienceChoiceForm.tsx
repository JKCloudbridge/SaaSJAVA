"use client";

import { useEffect, useId, useMemo, useState } from "react";
import type { Choice, ChoiceFormProps } from "@/lib/approvals/choiceForm";
import { getSuggestionOptions, type AudienceOptions } from "./api";

type Options =
  | { status: "loading" }
  | { status: "ready"; options: AudienceOptions }
  | { status: "failed"; message: string };

/**
 * The choice of an approval step that asks where the template is meant to be published (Functional track FA6G, ADR-0200):
 * tick the locations (at least one is required), and under each ticked location the departments of it (optional). It is a
 * suggestion for the person who publishes; nothing is shared and nobody gains access because of it. The lists are the
 * organization's own, as the API answers them; the server judges the choice again and refuses an approval without a
 * location, so this form only keeps the button from being pressed too early.
 */
export function AudienceChoiceForm({ recordId, onChange }: ChoiceFormProps) {
  const heading = useId();
  const [options, setOptions] = useState<Options>({ status: "loading" });
  // location code -> the department codes ticked under it
  const [picked, setPicked] = useState<Record<string, string[]>>({});

  useEffect(() => {
    let live = true;
    void getSuggestionOptions(recordId).then((outcome) => {
      if (live) {
        setOptions(
          outcome.ok ? { status: "ready", options: outcome.data } : { status: "failed", message: outcome.failure.message },
        );
      }
    });
    return () => {
      live = false;
    };
  }, [recordId]);

  const choices = useMemo<Choice[] | null>(() => {
    const locations = Object.keys(picked);
    if (locations.length === 0) {
      return null;
    }
    return locations.flatMap((location) => [
      { key: "location", value: location },
      ...(picked[location] ?? []).map((department) => ({ key: "department", value: `${location}/${department}` })),
    ]);
  }, [picked]);

  useEffect(() => {
    onChange(choices);
  }, [choices, onChange]);

  const toggleLocation = (location: string) =>
    setPicked((now) => {
      if (location in now) {
        return Object.fromEntries(Object.entries(now).filter(([held]) => held !== location));
      }
      return { ...now, [location]: [] };
    });

  const toggleDepartment = (location: string, department: string) =>
    setPicked((now) => {
      const held = now[location] ?? [];
      return {
        ...now,
        [location]: held.includes(department) ? held.filter((one) => one !== department) : [...held, department],
      };
    });

  return (
    <div className="stack" data-testid="audience-choice" role="group" aria-labelledby={heading}>
      <p id={heading}>
        <strong>Where is this template meant to be published?</strong> Choose at least one location. Departments are
        optional. This is a suggestion for the person who publishes: nobody sees the template because of it.
      </p>
      {options.status === "loading" ? (
        <p role="status" className="hint">
          Loading the locations…
        </p>
      ) : null}
      {options.status === "failed" ? (
        <p role="alert" className="form-message">
          {options.message}
        </p>
      ) : null}
      {options.status === "ready" ? (
        <div className="stack">
          {options.options.locations.map((place) => {
            const ticked = place.value in picked;
            return (
              <fieldset key={place.value} className="stack">
                <legend>
                  <label>
                    <input type="checkbox" checked={ticked} onChange={() => toggleLocation(place.value)} /> {place.label}
                  </label>
                </legend>
                {ticked && place.departments.length > 0 ? (
                  <div className="stack" role="group" aria-label={`Departments of ${place.label} (optional)`}>
                    {place.departments.map((part) => (
                      <label key={part.value}>
                        <input
                          type="checkbox"
                          checked={(picked[place.value] ?? []).includes(part.value)}
                          onChange={() => toggleDepartment(place.value, part.value)}
                        />{" "}
                        {part.label}
                      </label>
                    ))}
                  </div>
                ) : null}
              </fieldset>
            );
          })}
        </div>
      ) : null}
      {choices === null && options.status === "ready" ? (
        <p className="hint">Choose a location to approve.</p>
      ) : null}
    </div>
  );
}
