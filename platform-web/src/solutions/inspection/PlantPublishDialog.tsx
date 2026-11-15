"use client";

import { useEffect, useId, useMemo, useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import { ActionPlanFormPicker } from "./ActionPlanFormPicker";
import {
  addReviewer,
  getPlantOptions,
  isPlantChosen,
  plantsWithoutReviewer,
  publishToPlants,
  refusedPlace,
  removeReviewer,
  sameAsHeld,
  searchReviewers,
  selectionFrom,
  toRequest,
  togglePlant,
  type PlantOptions,
  type PlantSelection,
  type PlantState,
  type ReviewerChoices,
  type ReviewerEntry,
} from "./plantPublication";

type Options = { status: "loading" } | { status: "ready"; options: PlantOptions } | { status: "failed"; message: string };

/**
 * The dialog of "Publish to plants" (Functional track MC2A, ADR-0260): tick the plants the document goes to, then for
 * each plant choose its reviewers, people or groups, at least one. The plants offered are the places that have a group of
 * plant heads (the server says which; one without a group is shown and cannot be ticked). The people and groups are found
 * by typing part of a name: the server answers the search, this dialog holds no list. Saving sets the whole at once, so a
 * plant left unticked is taken back and a change of reviewers reaches only the reviews that start afterwards. The
 * server decides who may do it and why a choice is refused; the words and the place are shown as they come.
 */
export function PlantPublishDialog({
  templateId,
  state,
  inModal = false,
  onClose,
  onDone,
}: {
  templateId: string;
  state: PlantState;
  /** The popup around it gives the title and the frame: the dialog draws neither. */
  inModal?: boolean;
  onClose: () => void;
  onDone: (state: PlantState) => void;
}) {
  const heading = useId();
  const held = useMemo(() => selectionFrom(state.plants), [state.plants]);
  const [selection, setSelection] = useState<PlantSelection[]>(held);
  const [options, setOptions] = useState<Options>({ status: "loading" });
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();
  const [sent, setSent] = useState<PlantSelection[]>(held);

  useEffect(() => {
    let live = true;
    void getPlantOptions(templateId).then((outcome) => {
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

  const places = options.status === "ready" ? options.options.locations : [];
  const labelOf = (plant: string) => places.find((place) => place.value === plant)?.label ?? plant;
  const missing = plantsWithoutReviewer(selection);
  const unchanged = sameAsHeld(selection, held);
  const takingAll = selection.length === 0 && held.length > 0;

  const save = async () => {
    setBusy(true);
    setRefusal(undefined);
    setSent(selection);
    const outcome = await publishToPlants(templateId, toRequest(selection));
    setBusy(false);
    if (outcome.ok) {
      onDone(outcome.data);
      return;
    }
    setRefusal(outcome.failure);
  };

  // The server names the place of a refusal (plants[1] or plants[1].reviewers[0]): say it with the words people know.
  const placed = Object.entries(refusal?.fields ?? {}).flatMap(([place, messages]) => {
    const where = refusedPlace(place, sent);
    const label = [where.plant ? labelOf(where.plant) : undefined, where.reviewer].filter(Boolean).join(" · ");
    return messages.map((message) => ({ where: label || undefined, message }));
  });

  return (
    <div
      className={inModal ? "stack" : "card stack"}
      role={inModal ? undefined : "dialog"}
      aria-labelledby={inModal ? undefined : heading}
      data-testid="plant-publish-dialog"
    >
      {inModal ? null : <h3 id={heading}>Publish to plants</h3>}
      <p>
        The plant heads of the plants you tick can read this document and start their fill-in every month. For each plant
        choose who reviews its fill-ins. A change of reviewers reaches the reviews that start afterwards, never one that is
        already running.
      </p>

      {options.status === "loading" ? (
        <p role="status" className="hint">
          Loading the plants…
        </p>
      ) : null}
      {options.status === "failed" ? (
        <p role="alert" className="form-message">
          {options.message}
        </p>
      ) : null}

      {options.status === "ready" ? (
        <fieldset className="stack" data-testid="plant-places">
          <legend>Plants</legend>
          {places.length === 0 ? <p className="hint">No plant is set up to receive documents yet.</p> : null}
          {places.map((place) => {
            const configured = place.departments[0]?.configured !== false;
            const chosen = isPlantChosen(selection, place.value);
            return (
              <label key={place.value}>
                <input
                  type="checkbox"
                  checked={chosen}
                  disabled={!configured && !chosen}
                  onChange={() => {
                    setRefusal(undefined);
                    setSelection(togglePlant(selection, place.value));
                  }}
                />{" "}
                {configured ? place.label : `${place.label} (no group of plant heads set up yet)`}
              </label>
            );
          })}
        </fieldset>
      ) : null}

      <ActionPlanFormPicker templateId={templateId} />

      {selection.map((entry) => (
        <fieldset key={entry.plant} className="stack" data-testid={`plant-reviewers-${entry.plant}`}>
          <legend>Reviewers of {labelOf(entry.plant)}</legend>
          {entry.reviewers.length === 0 ? (
            <p className="hint">Choose at least one reviewer for this plant.</p>
          ) : (
            <ul className="stack" aria-label={`Reviewers of ${labelOf(entry.plant)}`}>
              {entry.reviewers.map((reviewer) => (
                <li key={`${reviewer.kind}:${reviewer.id}`} className="split">
                  <span>
                    {reviewer.name} <span className="hint">{reviewer.kind === "GROUP" ? "group" : "person"}</span>
                  </span>
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => setSelection(removeReviewer(selection, entry.plant, reviewer.kind, reviewer.id))}
                    aria-label={`Remove ${reviewer.name} from ${labelOf(entry.plant)}`}
                  >
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          )}
          <ReviewerPicker
            templateId={templateId}
            plantLabel={labelOf(entry.plant)}
            onPick={(reviewer) => {
              setRefusal(undefined);
              setSelection((now) => addReviewer(now, entry.plant, reviewer));
            }}
          />
        </fieldset>
      ))}

      {takingAll ? (
        <p className="hint" data-testid="plant-take-all">
          No plant is ticked, so saving takes this document back from every plant. Fill-ins that exist stay as they are.
        </p>
      ) : null}

      {refusal ? (
        <div role="alert" className="form-message" data-testid="plant-publish-refused">
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
        <button
          type="button"
          className="button"
          onClick={() => void save()}
          disabled={busy || missing.length > 0 || unchanged}
          data-testid="plant-publish-save"
        >
          {busy ? "Saving…" : "Save"}
        </button>
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}

/**
 * Finds a person or a group by part of the name and adds it. The server answers the search (a person needs the text to
 * match, so the answer cannot be used to list the directory); the text is never kept after a pick.
 */
function ReviewerPicker({
  templateId,
  plantLabel,
  onPick,
}: {
  templateId: string;
  plantLabel: string;
  onPick: (reviewer: ReviewerEntry) => void;
}) {
  const [text, setText] = useState("");
  const [found, setFound] = useState<{ text: string; choices: ReviewerChoices | null } | null>(null);

  useEffect(() => {
    const wanted = text.trim();
    if (wanted.length < 2) {
      return;
    }
    let live = true;
    const timer = setTimeout(() => {
      void searchReviewers(templateId, wanted).then((outcome) => {
        if (live) {
          setFound({ text: wanted, choices: outcome.ok ? outcome.data : null });
        }
      });
    }, 250);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [templateId, text]);

  const wanted = text.trim();
  const choices = found && found.text === wanted ? found.choices : null;
  const pick = (kind: "USER" | "GROUP", id: string, name: string) => {
    onPick({ kind, id, name });
    setText("");
    setFound(null);
  };

  return (
    <div className="stack">
      <label>
        Add a person or a group
        <input
          type="search"
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder="Type part of a name"
          aria-label={`Find a reviewer for ${plantLabel}`}
          autoComplete="off"
        />
      </label>
      {wanted.length >= 2 && found?.text === wanted && choices === null ? (
        <p className="hint">The search did not work. Try again.</p>
      ) : null}
      {choices && choices.people.length === 0 && choices.groups.length === 0 ? (
        <p className="hint">Nobody matches.</p>
      ) : null}
      {choices ? (
        <ul className="stack" aria-label="Matches">
          {choices.people.map((one) => (
            <li key={`USER:${one.id}`} className="split">
              <span>
                {one.label} <span className="hint">person</span>
              </span>
              <button type="button" className="link-button" onClick={() => pick("USER", one.id, one.label)}>
                Add
              </button>
            </li>
          ))}
          {choices.groups.map((one) => (
            <li key={`GROUP:${one.id}`} className="split">
              <span>
                {one.label} <span className="hint">group</span>
              </span>
              <button type="button" className="link-button" onClick={() => pick("GROUP", one.id, one.label)}>
                Add
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
