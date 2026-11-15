"use client";

import { useState } from "react";
import type { ApiFailure } from "@/lib/api/errors";
import { startFillIn, type FillInOptions, type FillInStarted } from "./fillIns";

/**
 * The popup of "Start a fill-in" for an application that asks for one fill-in each month for each plant (Functional
 * track MC2A, ADR-0260). The server says which plants the person may choose (those they are plant head of that the
 * document is published to) and which month is this month; this popup only carries the choice. A person of one plant is
 * shown the plant and asked for nothing; a person of several chooses. An earlier month may be chosen to catch up; the
 * month input stops at this month, and the server refuses a later one in any case. When a fill-in exists for the plant
 * and month, the server says so in its words and nothing is made.
 */
export function StartFillIn({
  templateId,
  options,
  onClose,
  onStarted,
}: {
  templateId: string;
  options: FillInOptions;
  onClose: () => void;
  onStarted: (started: FillInStarted) => void;
}) {
  const [plant, setPlant] = useState(options.plants.length === 1 ? options.plants[0]?.plant ?? "" : "");
  const [period, setPeriod] = useState(options.currentPeriod ?? "");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<ApiFailure | undefined>();

  const start = async () => {
    setBusy(true);
    setRefusal(undefined);
    const outcome = await startFillIn(templateId, { plant: plant || undefined, period: period || undefined });
    setBusy(false);
    if (outcome.ok) {
      onStarted(outcome.data);
      return;
    }
    setRefusal(outcome.failure);
  };

  const fieldMessages = (field: string) => refusal?.fields?.[field] ?? [];

  return (
    <div className="stack" data-testid="start-fill-in">
      <p>
        One fill-in is made for each plant and month. Choose the month you are filling in; an earlier month can be started
        to catch up.
      </p>
      {options.plants.length === 1 ? (
        <p>
          <strong>Plant:</strong> {options.plants[0]?.label}
        </p>
      ) : (
        <label>
          Plant
          <select value={plant} onChange={(event) => setPlant(event.target.value)} aria-invalid={fieldMessages("plant").length > 0}>
            <option value="">Choose a plant</option>
            {options.plants.map((one) => (
              <option key={one.plant} value={one.plant}>
                {one.label}
              </option>
            ))}
          </select>
        </label>
      )}
      {fieldMessages("plant").map((message) => (
        <p key={message} className="field-error">
          {message}
        </p>
      ))}
      <label>
        Month
        <input
          type="month"
          value={period}
          max={options.currentPeriod ?? undefined}
          onChange={(event) => setPeriod(event.target.value)}
          aria-invalid={fieldMessages("period").length > 0}
        />
      </label>
      {fieldMessages("period").map((message) => (
        <p key={message} className="field-error">
          {message}
        </p>
      ))}
      {refusal && Object.keys(refusal.fields ?? {}).length === 0 ? (
        <p role="alert" className="form-message">
          {refusal.message}
        </p>
      ) : null}
      <div className="split">
        <button
          type="button"
          className="button"
          onClick={() => void start()}
          disabled={busy || plant === "" || period === ""}
          data-testid="start-fill-in-go"
        >
          {busy ? "Starting…" : "Start"}
        </button>
        <button type="button" className="button button-secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}
