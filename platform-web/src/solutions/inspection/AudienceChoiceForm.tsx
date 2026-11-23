"use client";

import { useEffect, useMemo, useState } from "react";
import type { Choice, ChoiceFormProps } from "@/lib/approvals/choiceForm";
import { getSuggestionOptions, type AudienceOptions } from "./api";

type Options =
  | { status: "loading" }
  | { status: "ready"; options: AudienceOptions }
  | { status: "failed"; message: string };

/** One place of the list: a location, with a department or (departments are optional here) without one. */
interface Pick {
  location: string;
  department?: string;
}

/**
 * The choice of an approval step that asks where the template is meant to be published (Functional track FA6G, ADR-0200),
 * drawn like the Publish window: choose a location, tick the departments of it, add them to the list below, and choose
 * another location if needed. Here a department is optional: with no department ticked, "Add" adds the location alone.
 * It is a suggestion for the person who publishes; nothing is shared and nobody gains access because of it. The lists are
 * the organization's own, as the API answers them; the server judges the choice again and refuses an approval without a
 * location, so this form only keeps the button from being pressed too early.
 */
export function AudienceChoiceForm({ recordId, onChange }: ChoiceFormProps) {
  const [options, setOptions] = useState<Options>({ status: "loading" });
  const [chosen, setChosen] = useState<Pick[]>([]);
  const [location, setLocation] = useState("");
  const [ticks, setTicks] = useState<string[]>([]);

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

  const locations = useMemo(() => (options.status === "ready" ? options.options.locations : []), [options]);
  const departments = useMemo(
    () => locations.find((place) => place.value === location)?.departments ?? [],
    [locations, location],
  );

  const choices = useMemo<Choice[] | null>(() => {
    if (chosen.length === 0) {
      return null;
    }
    const places = [...new Set(chosen.map((pick) => pick.location))];
    return [
      ...places.map((place) => ({ key: "location", value: place })),
      ...chosen.flatMap((pick) =>
        pick.department ? [{ key: "department", value: `${pick.location}/${pick.department}` }] : [],
      ),
    ];
  }, [chosen]);

  useEffect(() => {
    onChange(choices);
  }, [choices, onChange]);

  const labelOf = (pick: Pick) => {
    const place = locations.find((entry) => entry.value === pick.location);
    const part = place?.departments.find((entry) => entry.value === pick.department);
    return part ? `${place?.label ?? pick.location} · ${part.label}` : (place?.label ?? pick.location);
  };
  const isChosen = (code: string) => chosen.some((pick) => pick.location === location && pick.department === code);

  const toggle = (code: string) =>
    setTicks((now) => (now.includes(code) ? now.filter((one) => one !== code) : [...now, code]));

  // The ticked departments of the location go in at once; with none ticked the location goes in alone.
  const add = () => {
    if (location === "") {
      return;
    }
    const wanted = departments.filter((part) => ticks.includes(part.value) && !isChosen(part.value));
    if (ticks.length > 0 && wanted.length === 0) {
      return;
    }
    const others = chosen.filter((pick) => !(pick.location === location && !pick.department));
    if (wanted.length === 0) {
      if (!chosen.some((pick) => pick.location === location)) {
        setChosen([...chosen, { location }]);
      }
    } else {
      setChosen([...others, ...wanted.map((part) => ({ location, department: part.value }))]);
    }
    setTicks([]);
  };

  return (
    <div className="stack" data-testid="audience-choice" role="group" aria-label="Where this template is meant to be published">
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
        <>
          <div className="filters">
            <label>
              Location
              <select
                value={location}
                onChange={(event) => {
                  setLocation(event.target.value);
                  setTicks([]);
                }}
              >
                <option value="">Choose a location</option>
                {locations.map((place) => (
                  <option key={place.value} value={place.value}>
                    {place.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <fieldset className="stack" disabled={location === ""}>
            <legend>Departments (optional)</legend>
            {location === "" ? <p className="hint">Choose a location first.</p> : null}
            {departments.map((part) => {
              const listed = isChosen(part.value);
              return (
                <label key={part.value}>
                  <input
                    type="checkbox"
                    checked={listed || ticks.includes(part.value)}
                    disabled={listed}
                    onChange={() => toggle(part.value)}
                  />{" "}
                  {part.label}
                </label>
              );
            })}
            <div>
              <button
                type="button"
                className="button button-secondary"
                onClick={add}
                disabled={location === "" || (ticks.length === 0 && chosen.some((pick) => pick.location === location))}
              >
                {ticks.length > 0 ? "Add the ticked departments" : "Add the location"}
              </button>
            </div>
          </fieldset>

          {chosen.length === 0 ? (
            <p className="hint">Nothing is chosen yet. Add at least one location.</p>
          ) : (
            <ul aria-label="Where it is suggested for" className="stack">
              {chosen.map((pick, index) => (
                <li key={`${pick.location}/${pick.department ?? ""}`} className="split">
                  <span>{labelOf(pick)}</span>
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => setChosen(chosen.filter((_, position) => position !== index))}
                    aria-label={`Remove ${labelOf(pick)}`}
                  >
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}
    </div>
  );
}
