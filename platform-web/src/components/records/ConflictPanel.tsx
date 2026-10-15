"use client";

import { useState } from "react";
import type { Conflict } from "@/lib/records/conflict";
import { formatValue, recordValue } from "@/lib/records/fieldValue";
import type { FormField, RecordView } from "@/lib/records/types";

/** What the person decides for a field both changed: keep what they typed, or take what the other change made. */
export type Choice = "mine" | "theirs";

/**
 * The recovery from "someone else changed this record while you were editing it" (Sprint 19, ADR-0129). The server
 * refused the save and stored nothing. The default offered is to reload and reapply: the newest version of the record
 * becomes the one the form is based on, the person's own changes stay on top of it wherever the other change did not
 * touch the same field, and where both changed the same field they choose. Nothing is saved from here: the person
 * reviews and saves again, and the server judges that save like any other (it can refuse again if the record moved
 * again). There is deliberately no way to overwrite the other change blindly.
 */
export function ConflictPanel({
  fields,
  conflict,
  latest,
  labels,
  onReapply,
  onDiscard,
  busy,
}: {
  fields: readonly FormField[];
  conflict: Conflict;
  latest: RecordView;
  labels: Record<string, Record<string, string>>;
  onReapply: (choices: Record<string, Choice>) => void;
  onDiscard: () => void;
  busy: boolean;
}) {
  const byName = new Map(fields.map((field) => [field.apiName, field]));
  const [choices, setChoices] = useState<Record<string, Choice>>(() =>
    Object.fromEntries(conflict.clashes.map((clash) => [clash.field, "mine" as Choice])),
  );
  const nameOf = (apiName: string) => byName.get(apiName)?.label ?? apiName;
  const theirText = (field: FormField) =>
    formatValue(field, recordValue(latest, field.apiName), labels[field.apiName]) || "(empty)";

  return (
    <section aria-labelledby="conflict-heading" data-testid="conflict-panel" className="conflict-panel">
      <h2 id="conflict-heading">This record was changed while you were editing it</h2>
      <p>
        Someone else saved a newer version, so your save was not made and nothing was lost. Reload it to continue: your
        changes are kept on top of the newer version wherever the other change did not touch the same field.
      </p>
      {conflict.theirChanges.length > 0 ? (
        <p data-testid="conflict-theirs">
          The other change also changed: <strong>{conflict.theirChanges.map(nameOf).join(", ")}</strong>. You will see
          the newer values.
        </p>
      ) : null}
      {conflict.reapply.length > 0 ? (
        <p data-testid="conflict-kept">
          Your changes that will be kept: <strong>{conflict.reapply.map(nameOf).join(", ")}</strong>.
        </p>
      ) : null}
      {conflict.clashes.length > 0 ? (
        <fieldset className="field" data-testid="conflict-clashes">
          <legend>Changed by both of you: choose which to keep</legend>
          {conflict.clashes.map((clash) => {
            const field = byName.get(clash.field);
            if (!field) {
              return null;
            }
            const name = `choice-${clash.field}`;
            return (
              <div key={clash.field} className="conflict-choice" role="radiogroup" aria-label={field.label}>
                <strong>{field.label}</strong>
                <label>
                  <input
                    type="radio"
                    name={name}
                    checked={choices[clash.field] === "mine"}
                    onChange={() => setChoices((current) => ({ ...current, [clash.field]: "mine" }))}
                  />
                  Keep mine
                </label>
                <label>
                  <input
                    type="radio"
                    name={name}
                    checked={choices[clash.field] === "theirs"}
                    onChange={() => setChoices((current) => ({ ...current, [clash.field]: "theirs" }))}
                  />
                  Use the newer value: {theirText(field)}
                </label>
              </div>
            );
          })}
        </fieldset>
      ) : null}
      <div className="filters-actions">
        <button type="button" className="button" disabled={busy} onClick={() => onReapply(choices)}>
          Reload and reapply my changes
        </button>
        <button type="button" className="button button-secondary" disabled={busy} onClick={onDiscard}>
          Discard my changes and reload
        </button>
      </div>
    </section>
  );
}
