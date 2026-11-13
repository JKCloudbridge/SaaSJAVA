"use client";

import { useEffect, useId, useMemo, useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import {
  getAudienceOptions,
  publishTemplate,
  type AudienceOptions,
  type AudiencePick,
  type TemplateLifecycleView,
} from "./api";
import { revisionWords } from "@/lib/sheets/revision";

type Options = { status: "loading" } | { status: "ready"; options: AudienceOptions } | { status: "failed"; message: string };

/**
 * The dialog of Publish (Functional track FA4, ADR-0188): choose a Location, then tick the Departments of it (the
 * choices narrow to the location chosen, as the organization keeps them), add the ticked ones at once, choose another
 * location and tick again if needed, and publish. The
 * people of each department are a group an administrator keeps; the server finds the group, so this dialog names none.
 *
 * <p>When a step of the review suggested where the template is meant to be published (FA6G, ADR-0200) the dialog starts from
 * that suggestion, which the person may change: the departments suggested are listed ready, and a location suggested
 * without a department is already chosen, waiting for its departments. A suggestion shares nothing; only Publish does.
 * Otherwise it shows the audience the template already has and keeps it unless the approver changes it. It offers every department
 * of the organization's list, marking those without a group, because the server is the one that says "no group is set up
 * for this department yet" in its own words; a refusal names the pair it is about. Nothing here decides a permission.
 */
export function PublishDialog({
  templateId,
  lifecycle,
  inModal = false,
  onClose,
  onDone,
}: {
  templateId: string;
  lifecycle: TemplateLifecycleView;
  /** The popup around it gives the title and the frame: the dialog draws neither. */
  inModal?: boolean;
  onClose: () => void;
  onDone: (view: TemplateLifecycleView) => void;
}) {
  const heading = useId();
  const [options, setOptions] = useState<Options>({ status: "loading" });
  const suggested = lifecycle.suggestedAudience;
  const [chosen, setChosen] = useState<AudiencePick[]>(() =>
    suggested.length > 0
      ? suggested.flatMap((place) =>
          place.department ? [{ location: place.location, department: place.department }] : [],
        )
      : lifecycle.audience.map((audience) => ({ location: audience.location, department: audience.department })),
  );
  // The first suggested location is chosen already, so its suggested departments show ticked and the person only has to
  // add what is missing (a location suggested alone waits for its departments).
  const [location, setLocation] = useState(
    () => (suggested.find((place) => !place.department) ?? suggested[0])?.location ?? "",
  );
  const [ticks, setTicks] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();
  // The two options of the sheet (FA7): both start unticked on a template that never had them set.
  const [viewOnly, setViewOnly] = useState(lifecycle.viewOnly);
  const [allowDownload, setAllowDownload] = useState(lifecycle.allowDownload);

  useEffect(() => {
    let live = true;
    void getAudienceOptions().then((outcome) => {
      if (live) {
        setOptions(
          outcome.ok ? { status: "ready", options: outcome.data } : { status: "failed", message: outcome.failure.message },
        );
      }
    });
    return () => {
      live = false;
    };
  }, []);

  const numbers = lifecycle.lifecycle;
  // The version to make current: the approved one waiting, else the one in use (only who sees it changes).
  const versionNo = numbers.approvedVersion > 0 ? numbers.approvedVersion : numbers.activeVersion;
  const changesVersion = numbers.approvedVersion > 0 && numbers.approvedVersion !== numbers.activeVersion;

  const locations = useMemo(() => (options.status === "ready" ? options.options.locations : []), [options]);
  const departments = useMemo(
    () => locations.find((place) => place.value === location)?.departments ?? [],
    [locations, location],
  );
  // An application that publishes to places alone (MC1, ADR-0257): the server names the one department that receives a
  // template at each place, and the dialog asks for places only.
  const placesOnly = options.status === "ready" && options.options.placesOnly === true;
  const placeDepartment = options.status === "ready" ? options.options.placeDepartment ?? "" : "";
  const labelOf = (pick: AudiencePick) => {
    const place = locations.find((entry) => entry.value === pick.location);
    const part = place?.departments.find((entry) => entry.value === pick.department);
    return placesOnly ? (place?.label ?? pick.location) : `${place?.label ?? pick.location} · ${part?.label ?? pick.department}`;
  };
  const isPlaceChosen = (code: string) =>
    chosen.some((pick) => pick.location === code && pick.department === placeDepartment);
  const togglePlace = (code: string) => {
    setRefusal(undefined);
    setChosen(
      isPlaceChosen(code)
        ? chosen.filter((pick) => !(pick.location === code && pick.department === placeDepartment))
        : [...chosen, { location: code, department: placeDepartment }],
    );
  };
  const isChosen = (code: string) => chosen.some((pick) => pick.location === location && pick.department === code);

  const toggle = (code: string) =>
    setTicks((now) => (now.includes(code) ? now.filter((one) => one !== code) : [...now, code]));

  // Every ticked department of the location goes into the list at once, in the order the organization's list has them.
  const add = () => {
    const wanted = departments.filter((part) => ticks.includes(part.value) && !isChosen(part.value));
    if (location === "" || wanted.length === 0) {
      return;
    }
    setChosen([...chosen, ...wanted.map((part) => ({ location, department: part.value }))]);
    setTicks([]);
    setRefusal(undefined);
  };

  const submit = async () => {
    setBusy(true);
    setRefusal(undefined);
    const outcome = await publishTemplate(templateId, { versionNo, audiences: chosen, viewOnly, allowDownload });
    setBusy(false);
    if (outcome.ok) {
      onDone(outcome.data);
      return;
    }
    setRefusal(outcome.failure);
  };

  // The server names the pair that cannot be published to by its place, audiences[2]: say it with the pair's own words.
  const placed = Object.entries(refusal?.fields ?? {}).flatMap(([place, messages]) => {
    const index = /^audiences\[(\d+)\]$/.exec(place)?.[1];
    const pick = index === undefined ? undefined : chosen[Number(index)];
    return messages.map((message) => ({ where: pick ? labelOf(pick) : undefined, message }));
  });

  return (
    <div
      className={inModal ? "stack" : "card stack"}
      role={inModal ? undefined : "dialog"}
      aria-labelledby={inModal ? undefined : heading}
      data-testid="publish-dialog"
    >
      {inModal ? null : <h3 id={heading}>Publish this template</h3>}
      <p>
        {changesVersion
          ? `${revisionWords(versionNo)} becomes the one in use and replaces ${numbers.activeVersion > 0 ? revisionWords(numbers.activeVersion).toLowerCase() : "nothing yet"}.`
          : `${revisionWords(versionNo)} stays in use. Only who it is published to changes.`}{" "}
        {placesOnly
          ? "The people of the places you choose will see it in the Templates list, and nobody else will."
          : "The people of the departments you choose will see it in the Templates list, and nobody else will."}
      </p>

      {suggested.length > 0 ? (
        <p className="hint" data-testid="publish-suggested">
          Filled in from the places suggested when this template was approved. Change them if they are not right; nobody
          sees the template until you publish.
        </p>
      ) : null}
      {lifecycle.suggestionDropped > 0 ? (
        <p className="hint" data-testid="publish-suggestion-dropped">
          {lifecycle.suggestionDropped === 1
            ? "One suggested place is no longer offered and was left out."
            : `${lifecycle.suggestionDropped} suggested places are no longer offered and were left out.`}
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

      {options.status === "ready" && placesOnly ? (
        <fieldset className="stack" data-testid="publish-places">
          <legend>Places</legend>
          {locations.length === 0 ? <p className="hint">No place is set up to receive templates yet.</p> : null}
          {locations.map((place) => {
            const part = place.departments[0];
            return (
              <label key={place.value}>
                <input type="checkbox" checked={isPlaceChosen(place.value)} onChange={() => togglePlace(place.value)} />{" "}
                {part?.configured === false ? `${place.label} (no group set up yet)` : place.label}
              </label>
            );
          })}
        </fieldset>
      ) : null}

      {options.status === "ready" && !placesOnly ? (
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
                  {part.configured ? part.label : `${part.label} (no group set up yet)`}
                </label>
              );
            })}
            <div>
              <button type="button" className="button button-secondary" onClick={add} disabled={ticks.length === 0}>
                Add the ticked departments
              </button>
            </div>
          </fieldset>

          {chosen.length === 0 ? (
            <p className="hint">No department is chosen yet. Add at least one.</p>
          ) : (
            <ul aria-label="Departments it is published to" className="stack">
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
        </>
      ) : null}

      <div className="sheet-options-row" role="group" aria-label="Sheet options" data-testid="publish-sheet-options">
        <label>
          <input type="checkbox" checked={viewOnly} onChange={(event) => setViewOnly(event.target.checked)} /> View only
        </label>
        <label>
          <input type="checkbox" checked={allowDownload} onChange={(event) => setAllowDownload(event.target.checked)} /> Allow
          download
        </label>
      </div>
      <p className="hint">
        View only: the template is a reference and nobody can start a fill-in from it. Allow download: everybody who can read
        the template can download its sheet as an Excel file. You can change both later from Sheet options.
      </p>

      {refusal ? (
        <div role="alert" className="form-message" data-testid="publish-refused">
          <p>{refusal.message}</p>
          {placed.length > 0 ? (
            <ul>
              {placed.map((entry, index) => (
                <li key={index}>
                  {entry.where ? <strong>{entry.where}: </strong> : null}
                  {entry.message}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      <div className="split">
        <button type="button" className="button" onClick={() => void submit()} disabled={busy || chosen.length === 0}>
          {busy ? "Publishing…" : "Publish"}
        </button>
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}
