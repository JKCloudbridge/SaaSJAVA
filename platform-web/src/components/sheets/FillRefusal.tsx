"use client";

import { explainFillPlace } from "@/lib/sheets/fillModel";
import type { FillDoc } from "@/lib/sheets/fillTypes";
import type { GridDoc, Problem } from "@/lib/sheets/types";

/** What the server said when it refused a save of the cells: the words, the places that are wrong, and the way out of a clash. */
export interface FillRefusalState {
  sent: FillDoc;
  problems: Problem[];
  message: string;
  /** The row version was out of date: offer to read the fill-in again. */
  stale: boolean;
}

/**
 * The notice of a refused save (FF1), drawn in the API's own words with the place that is wrong. One component for the two
 * places that show it, the Sheet tab and the View page (SH_6), so that a refusal reads the same wherever the person is.
 * It decides nothing: the buttons call what the page gives it.
 */
export function FillRefusal({
  grid,
  refusal,
  onReload,
  onReloadAndReapply,
}: {
  grid: GridDoc;
  refusal: FillRefusalState;
  onReload(): void;
  onReloadAndReapply(): void;
}) {
  return (
    <div role="alert" className="form-message" data-testid="save-refused">
      <p>{refusal.message}</p>
      {refusal.problems.length > 0 ? (
        <ul>
          {refusal.problems.map((problem, i) => (
            <li key={`${problem.place}-${i}`}>
              <strong>{explainFillPlace(grid, refusal.sent, problem.place)}:</strong> {problem.message}
            </li>
          ))}
        </ul>
      ) : null}
      {refusal.stale ? (
        <p>
          <button type="button" className="button button-secondary" onClick={onReload}>
            Reload this fill-in
          </button>{" "}
          <button type="button" className="button button-secondary" onClick={onReloadAndReapply}>
            Reload and reapply my changes
          </button>{" "}
          <span className="hint">Your unsaved changes are lost when you reload.</span>
        </p>
      ) : null}
    </div>
  );
}
