"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Tag } from "@/components/ui/lists/Tag";
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
type Place = AudienceOptions["locations"][number];

const keyOf = (location: string, department: string) => `${location}/${department}`;

/**
 * The dialog of Publish (Functional track FA4, ADR-0188; the layout of FA15, ADR-0265). One section for each location,
 * all headers visible with their status; under an open header the departments of that location are a column of tick
 * boxes and ticking is direct. A summary line above Publish says in words what will happen, including what will be
 * taken back.
 *
 * <p>The server decides who may publish where; this dialog draws what it says. When the approval chose locations
 * (FA6G, ADR-0200) they come first and are marked; a person who is limited to them (`limitedToSuggested`) sees only
 * those, plus a read-only note of what others published elsewhere, which a publish of theirs never changes. The
 * departments the approval ticked are hints, pre-ticked. What is already published starts ticked, so that nothing is
 * taken back by accident; unticking it is how it is taken back. Nothing here decides a permission, and a refusal is
 * shown in the API's words with the place it names.
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
  const listRef = useRef<HTMLDivElement>(null);
  const [options, setOptions] = useState<Options>({ status: "loading" });
  const suggested = lifecycle.suggestedAudience;
  const limited = lifecycle.limitedToSuggested;
  const noPlaceLeft = lifecycle.noPlaceLeft;
  const suggestedLocations = useMemo(() => [...new Set(suggested.map((place) => place.location))], [suggested]);
  const publishedKeys = useMemo(
    () => new Set(lifecycle.audience.map((entry) => keyOf(entry.location, entry.department))),
    [lifecycle.audience],
  );
  // What is published now and the departments the approval ticked: the union, so nothing already published is hidden.
  const [ticked, setTicked] = useState<Set<string>>(
    () =>
      new Set([
        ...publishedKeys,
        ...suggested.flatMap((place) => (place.department ? [keyOf(place.location, place.department)] : [])),
      ]),
  );
  const [open, setOpen] = useState<string | undefined>(suggestedLocations[0] ?? lifecycle.audience[0]?.location);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();
  const [lastSent, setLastSent] = useState<AudiencePick[]>([]);
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

  const locations = useMemo<Place[]>(() => (options.status === "ready" ? options.options.locations : []), [options]);
  // An application that publishes to places alone (MC1, ADR-0257): the server names the one department that receives a
  // template at each place, and the dialog asks for places only.
  const placesOnly = options.status === "ready" && options.options.placesOnly === true;
  const placeDepartment = options.status === "ready" ? options.options.placeDepartment ?? "" : "";
  const inScope = (location: string) => !limited || suggestedLocations.includes(location);
  const labelOf = (pick: AudiencePick) => {
    const place = locations.find((entry) => entry.value === pick.location);
    const part = place?.departments.find((entry) => entry.value === pick.department);
    return placesOnly ? (place?.label ?? pick.location) : `${place?.label ?? pick.location} · ${part?.label ?? pick.department}`;
  };

  // The locations drawn, in groups: the ones the approval chose first, the others quieter (only for who may).
  const chosenPlaces = suggestedLocations.flatMap((code) => locations.filter((place) => place.value === code));
  const otherPlaces = locations.filter((place) => !suggestedLocations.includes(place.value));
  const shown = suggestedLocations.length === 0 ? locations : chosenPlaces.concat(limited ? [] : otherPlaces);
  const elsewhere = limited ? lifecycle.audience.filter((entry) => !suggestedLocations.includes(entry.location)) : [];

  const toggle = (location: string, department: string) => {
    setRefusal(undefined);
    setTicked((now) => {
      const next = new Set(now);
      const key = keyOf(location, department);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  // What will be sent: the ticked pairs of the places the person may publish to, in the order of the organization's lists.
  const sendPairs: AudiencePick[] = shown.flatMap((place) =>
    placesOnly
      ? ticked.has(keyOf(place.value, placeDepartment))
        ? [{ location: place.value, department: placeDepartment }]
        : []
      : place.departments
          .filter((part) => ticked.has(keyOf(place.value, part.value)))
          .map((part) => ({ location: place.value, department: part.value })),
  );
  const sentLocations = new Set(sendPairs.map((pick) => pick.location));
  const notYet = chosenPlaces.filter((place) => !sentLocations.has(place.value)).map((place) => place.label);
  const takenBack = [...publishedKeys].filter((key) => inScope(key.split("/")[0] ?? "") && !ticked.has(key)).length;
  const summary =
    sendPairs.length === 0
      ? "Nothing is chosen yet."
      : `Publishing to ${sentLocations.size} ${sentLocations.size === 1 ? "location" : "locations"}, ${sendPairs.length} ${
          placesOnly ? (sendPairs.length === 1 ? "place" : "places") : sendPairs.length === 1 ? "department" : "departments"
        }.` +
        (notYet.length > 0 ? ` Not yet chosen: ${notYet.join(", ")}.` : "") +
        (takenBack > 0 ? ` Will be taken back: ${takenBack}.` : "");

  const statusOf = (place: Place): string => {
    const departments = place.departments;
    const own = departments.filter((part) => ticked.has(keyOf(place.value, part.value)));
    const was = departments.filter((part) => publishedKeys.has(keyOf(place.value, part.value)));
    if (own.length === 0) {
      return was.length > 0 ? "Will be taken back" : suggestedLocations.includes(place.value) ? "No department yet" : "None chosen";
    }
    const same = own.length === was.length && own.every((part) => publishedKeys.has(keyOf(place.value, part.value)));
    return same ? "Published" : `${own.length} of ${departments.length} departments`;
  };

  const submit = async () => {
    setBusy(true);
    setRefusal(undefined);
    setLastSent(sendPairs);
    const outcome = await publishTemplate(templateId, { versionNo, audiences: sendPairs, viewOnly, allowDownload });
    setBusy(false);
    if (outcome.ok) {
      onDone(outcome.data);
      return;
    }
    setRefusal(outcome.failure);
  };

  // Arrow keys move between the headers of the sections; Enter and Space open one, as on any button.
  const moveBetweenHeaders = (event: React.KeyboardEvent<HTMLButtonElement>) => {
    const headers = [...(listRef.current?.querySelectorAll<HTMLButtonElement>("button[data-section-header]") ?? [])];
    const at = headers.indexOf(event.currentTarget);
    const next =
      event.key === "ArrowDown" ? at + 1 : event.key === "ArrowUp" ? at - 1 : event.key === "Home" ? 0 : event.key === "End" ? headers.length - 1 : -1;
    if (next >= 0 && next < headers.length) {
      event.preventDefault();
      headers[next]?.focus();
    }
  };

  // The server names the pair that cannot be published to by its place, audiences[2]: say it with the pair's own words.
  const placed = Object.entries(refusal?.fields ?? {}).flatMap(([place, messages]) => {
    const index = /^audiences\[(\d+)\]$/.exec(place)?.[1];
    const pick = index === undefined ? undefined : lastSent[Number(index)];
    return messages.map((message) => ({ where: pick ? labelOf(pick) : undefined, message }));
  });

  const section = (place: Place, byApproval: boolean) => {
    const isOpen = open === place.value;
    const panel = `${heading}-${place.value}`;
    return (
      <div key={place.value} className="stack" data-testid={`publish-section-${place.value}`}>
        <h4>
          <button
            type="button"
            className="link-button"
            data-section-header=""
            aria-expanded={isOpen}
            aria-controls={panel}
            onClick={() => setOpen(isOpen ? "" : place.value)}
            onKeyDown={moveBetweenHeaders}
          >
            {isOpen ? "▾" : "▸"} {place.label}{" "}
            {byApproval ? <Tag tone="on">Chosen by Corporate QMS</Tag> : null} <Tag>{statusOf(place)}</Tag>
          </button>
        </h4>
        {isOpen ? (
          <div id={panel} role="group" aria-label={`Departments of ${place.label}`} className="stack">
            {placesOnly ? (
              <label>
                <input
                  type="checkbox"
                  checked={ticked.has(keyOf(place.value, placeDepartment))}
                  onChange={() => toggle(place.value, placeDepartment)}
                />{" "}
                {place.departments[0]?.configured === false ? `${place.label} (no group set up yet)` : place.label}
              </label>
            ) : (
              place.departments.map((part) => (
                <label key={part.value}>
                  <input
                    type="checkbox"
                    checked={ticked.has(keyOf(place.value, part.value))}
                    onChange={() => toggle(place.value, part.value)}
                  />{" "}
                  {part.configured ? part.label : `${part.label} (no group set up yet)`}
                  {publishedKeys.has(keyOf(place.value, part.value)) ? (
                    <>
                      {" "}
                      <Tag tone="ok">Published</Tag>
                    </>
                  ) : null}
                </label>
              ))
            )}
          </div>
        ) : null}
      </div>
    );
  };

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
          {limited
            ? "You can publish to the locations chosen when this template was approved. The departments ticked there are a suggestion: tick the ones that are right. Nobody sees the template until you publish."
            : "Filled in from the places suggested when this template was approved. Change them if they are not right; nobody sees the template until you publish."}
        </p>
      ) : null}
      {lifecycle.suggestionDropped > 0 ? (
        <p className="hint" data-testid="publish-suggestion-dropped">
          {lifecycle.suggestionDropped === 1
            ? "One suggested place is no longer offered and was left out."
            : `${lifecycle.suggestionDropped} suggested places are no longer offered and were left out.`}
        </p>
      ) : null}
      {noPlaceLeft ? (
        <p role="alert" className="form-message" data-testid="publish-no-place-left">
          No location chosen for this document is offered any more. Ask somebody who may publish to any location.
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
        <div ref={listRef} className="stack" data-testid="publish-places">
          {suggestedLocations.length > 0 ? <strong>Places Corporate QMS chose</strong> : null}
          {chosenPlaces.map((place) => section(place, true))}
          {suggestedLocations.length === 0 ? locations.map((place) => section(place, false)) : null}
          {suggestedLocations.length > 0 && !limited && otherPlaces.length > 0 ? (
            <>
              <strong className="hint">Other locations</strong>
              {otherPlaces.map((place) => section(place, false))}
            </>
          ) : null}
          {locations.length === 0 ? <p className="hint">No place is set up to receive templates yet.</p> : null}
        </div>
      ) : null}

      {elsewhere.length > 0 ? (
        <div data-testid="publish-elsewhere">
          <p className="hint">Published elsewhere by someone else. A publish of yours leaves these as they are:</p>
          <ul className="stack">
            {elsewhere.map((entry) => (
              <li key={keyOf(entry.location, entry.department)}>
                {entry.locationLabel} · {entry.departmentLabel}
              </li>
            ))}
          </ul>
        </div>
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

      {options.status === "ready" ? (
        <p role="status" data-testid="publish-summary">
          {summary}
        </p>
      ) : null}
      {notYet.length > 0 ? (
        <p className="hint" data-testid="publish-gaps">
          Not published yet: {notYet.join(", ")}.
        </p>
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
        <button type="button" className="button" onClick={() => void submit()} disabled={busy || sendPairs.length === 0 || noPlaceLeft}>
          {busy ? "Publishing…" : "Publish"}
        </button>
        {sendPairs.length === 0 && !noPlaceLeft && options.status === "ready" ? (
          <span className="hint" data-testid="publish-why-disabled">
            Tick at least one department to publish.
          </span>
        ) : null}
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}
