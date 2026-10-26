"use client";

import { useEffect, useId, useMemo, useState } from "react";
import type { Choice } from "@/lib/approvals/choiceForm";
import { getSuggestionOptions, type AudienceOptions, type AudiencePick } from "./api";
import type { PublishPrefill } from "./changeRequests";

type Options =
  | { status: "loading" }
  | { status: "ready"; options: AudienceOptions }
  | { status: "failed"; message: string };

/**
 * The Publish window of the last approval of a change request (Functional track FA8, ADR-0202): the same window as for a new
 * template (choose a Location, tick the Departments of it, add them to the list, choose another location if needed) and the
 * two options of the sheet, View only and Allow download. It starts from what the step that asked for a choice suggested and
 * from the options the template has now. At least one location and, for each, at least one department are needed, as for a
 * template. Confirming the window is the last approval and the publish together. Nothing here decides: the server judges
 * the choice again, needs the Publisher access policy and refuses an approval without the revised copy.
 */
export function PublishChoiceForm({
  templateId,
  prefill,
  onChange,
}: {
  templateId: string;
  prefill: PublishPrefill | undefined;
  onChange: (choices: Choice[] | null) => void;
}) {
  const heading = useId();
  const [options, setOptions] = useState<Options>({ status: "loading" });
  const suggested = prefill?.suggested ?? [];
  const [chosen, setChosen] = useState<AudiencePick[]>(() =>
    suggested.flatMap((place) => (place.department ? [{ location: place.location, department: place.department }] : [])),
  );
  const [location, setLocation] = useState(() => (suggested.find((place) => !place.department) ?? suggested[0])?.location ?? "");
  const [ticks, setTicks] = useState<string[]>([]);
  const [viewOnly, setViewOnly] = useState(prefill?.viewOnly ?? false);
  const [allowDownload, setAllowDownload] = useState(prefill?.allowDownload ?? false);

  useEffect(() => {
    let live = true;
    void getSuggestionOptions(templateId).then((outcome) => {
      if (live) {
        setOptions(
          outcome.ok ? { status: "ready", options: outcome.data } : { status: "failed", message: outcome.failure.message },
        );
      }
    });
    return () => {
      live = false;
    };
  }, [templateId]);

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
      ...chosen.map((pick) => ({ key: "department", value: `${pick.location}/${pick.department}` })),
      ...(viewOnly ? [{ key: "option", value: "viewOnly" }] : []),
      ...(allowDownload ? [{ key: "option", value: "allowDownload" }] : []),
    ];
  }, [chosen, viewOnly, allowDownload]);

  useEffect(() => {
    onChange(choices);
  }, [choices, onChange]);

  const labelOf = (pick: AudiencePick) => {
    const place = locations.find((entry) => entry.value === pick.location);
    const part = place?.departments.find((entry) => entry.value === pick.department);
    return `${place?.label ?? pick.location} · ${part?.label ?? pick.department}`;
  };
  const isChosen = (code: string) => chosen.some((pick) => pick.location === location && pick.department === code);
  const toggle = (code: string) =>
    setTicks((now) => (now.includes(code) ? now.filter((one) => one !== code) : [...now, code]));

  // Every ticked department of the location goes into the list at once.
  const add = () => {
    const wanted = departments.filter((part) => ticks.includes(part.value) && !isChosen(part.value));
    if (location === "" || wanted.length === 0) {
      return;
    }
    setChosen([...chosen, ...wanted.map((part) => ({ location, department: part.value }))]);
    setTicks([]);
  };

  return (
    <div className="stack" data-testid="publish-choice" role="group" aria-labelledby={heading}>
      <p id={heading}>
        <strong>Who is this revision published to?</strong> Choose a location, then tick its departments, as for a new
        template. Confirming this window gives the last approval and publishes the revised sheet as the next revision.
      </p>
      {suggested.length > 0 ? (
        <p className="hint" data-testid="publish-choice-suggested">
          Filled in from the places suggested at the earlier step. Change them if they are not right.
        </p>
      ) : null}
      {prefill && prefill.suggestionDropped > 0 ? (
        <p className="hint" role="note">
          {prefill.suggestionDropped === 1
            ? "One suggested place is no longer offered by the lists and was left out."
            : `${prefill.suggestionDropped} suggested places are no longer offered by the lists and were left out.`}
        </p>
      ) : null}
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
            <legend>Departments</legend>
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
              <button type="button" className="button button-secondary" onClick={add} disabled={location === "" || ticks.length === 0}>
                Add the ticked departments
              </button>
            </div>
          </fieldset>
          {chosen.length === 0 ? (
            <p className="hint">Nothing is chosen yet. Add at least one department.</p>
          ) : (
            <ul aria-label="Published to" className="stack">
              {chosen.map((pick, index) => (
                <li key={`${pick.location}/${pick.department}`} className="split">
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
          <div className="split">
            <label className="sheet-check">
              <input type="checkbox" checked={viewOnly} onChange={(event) => setViewOnly(event.target.checked)} />
              <span>View only</span>
            </label>
            <label className="sheet-check">
              <input type="checkbox" checked={allowDownload} onChange={(event) => setAllowDownload(event.target.checked)} />
              <span>Allow download</span>
            </label>
          </div>
        </>
      ) : null}
    </div>
  );
}
