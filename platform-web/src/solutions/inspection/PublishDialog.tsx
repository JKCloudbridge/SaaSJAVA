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

type Options = { status: "loading" } | { status: "ready"; options: AudienceOptions } | { status: "failed"; message: string };

/**
 * The dialog of Publish (Functional track FA4, ADR-0188): choose a Location, then a Department (the choices of the
 * department narrow to the location chosen, as the organization keeps them), add as many as are needed, and publish. The
 * people of each department are a group an administrator keeps; the server finds the group, so this dialog names none.
 *
 * <p>It shows the audience the template already has and keeps it unless the approver changes it. It offers every department
 * of the organization's list, marking those without a group, because the server is the one that says "no group is set up
 * for this department yet" in its own words; a refusal names the pair it is about. Nothing here decides a permission.
 */
export function PublishDialog({
  templateId,
  lifecycle,
  onClose,
  onDone,
}: {
  templateId: string;
  lifecycle: TemplateLifecycleView;
  onClose: () => void;
  onDone: (view: TemplateLifecycleView) => void;
}) {
  const heading = useId();
  const [options, setOptions] = useState<Options>({ status: "loading" });
  const [chosen, setChosen] = useState<AudiencePick[]>(() =>
    lifecycle.audience.map((audience) => ({ location: audience.location, department: audience.department })),
  );
  const [location, setLocation] = useState("");
  const [department, setDepartment] = useState("");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();

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
  const labelOf = (pick: AudiencePick) => {
    const place = locations.find((entry) => entry.value === pick.location);
    const part = place?.departments.find((entry) => entry.value === pick.department);
    return `${place?.label ?? pick.location} · ${part?.label ?? pick.department}`;
  };
  const already = chosen.some((pick) => pick.location === location && pick.department === department);

  const add = () => {
    if (location === "" || department === "" || already) {
      return;
    }
    setChosen([...chosen, { location, department }]);
    setDepartment("");
    setRefusal(undefined);
  };

  const submit = async () => {
    setBusy(true);
    setRefusal(undefined);
    const outcome = await publishTemplate(templateId, { versionNo, audiences: chosen });
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
    <div className="card stack" role="dialog" aria-labelledby={heading} data-testid="publish-dialog">
      <h3 id={heading}>Publish this template</h3>
      <p>
        {changesVersion
          ? `Version ${versionNo} becomes the one in use and replaces ${numbers.activeVersion > 0 ? `version ${numbers.activeVersion}` : "nothing yet"}.`
          : `Version ${versionNo} stays in use. Only who it is published to changes.`}{" "}
        The people of the departments you choose will see it in the Templates list, and nobody else will.
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
        <>
          <div className="filters">
            <label>
              Location
              <select
                value={location}
                onChange={(event) => {
                  setLocation(event.target.value);
                  setDepartment("");
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
            <label>
              Department
              <select value={department} onChange={(event) => setDepartment(event.target.value)} disabled={location === ""}>
                <option value="">{location === "" ? "Choose a location first" : "Choose a department"}</option>
                {departments.map((part) => (
                  <option key={part.value} value={part.value}>
                    {part.configured ? part.label : `${part.label} (no group set up yet)`}
                  </option>
                ))}
              </select>
            </label>
            <div className="filters-actions">
              <button type="button" className="button button-secondary" onClick={add} disabled={department === "" || already}>
                Add this department
              </button>
            </div>
          </div>

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
