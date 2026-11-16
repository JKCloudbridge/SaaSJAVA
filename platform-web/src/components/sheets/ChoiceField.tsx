"use client";

import { useId, useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { isChoice, type FillChoice, type FillValue } from "@/lib/sheets/fillTypes";

/**
 * The answer and the reason of a Yes/No cell with a reason (SH5.1, ADR-0263): the prompt the author wrote, a Yes and a
 * No, and a box for the reason under them. The reason is never compulsory. It decides nothing: the editor keeps the
 * value, and the server checks it again on save.
 */

/** What the person sees and changes: the answer as a word of the radio group, and the reason as typed. */
interface Draft {
  answer: "yes" | "no" | "";
  reason: string;
}

function draftOf(value: FillValue | undefined): Draft {
  const choice = isChoice(value) ? value : {};
  return {
    answer: choice.answer === true ? "yes" : choice.answer === false ? "no" : "",
    reason: choice.reason ?? "",
  };
}

function choiceOf(draft: Draft): FillChoice {
  return {
    ...(draft.answer === "yes" ? { answer: true } : draft.answer === "no" ? { answer: false } : {}),
    ...(draft.reason !== "" ? { reason: draft.reason } : {}),
  };
}

/** The controls themselves: the prompt, the answer and the reason. */
function ChoiceControls({
  prompt,
  draft,
  required,
  onAnswer,
  onReason,
  onReasonDone,
}: {
  prompt: string;
  draft: Draft;
  required: boolean;
  onAnswer(answer: Draft["answer"]): void;
  onReason(reason: string): void;
  onReasonDone?(): void;
}) {
  const id = useId();
  return (
    <div className="stack">
      <fieldset className="field">
        <legend>
          {prompt || "Answer"}
          {required ? <span aria-hidden="true"> *</span> : null}
          {required ? <span className="visually-hidden"> (the answer is required)</span> : null}
        </legend>
        <label>
          <input type="radio" name={`${id}-answer`} checked={draft.answer === "yes"} onChange={() => onAnswer("yes")} /> Yes
        </label>
        <label>
          <input type="radio" name={`${id}-answer`} checked={draft.answer === "no"} onChange={() => onAnswer("no")} /> No
        </label>
        <label>
          <input type="radio" name={`${id}-answer`} checked={draft.answer === ""} onChange={() => onAnswer("")} /> No answer
          yet
        </label>
      </fieldset>
      <div className="field">
        <label htmlFor={`${id}-reason`}>Reason (optional)</label>
        <textarea
          id={`${id}-reason`}
          rows={2}
          value={draft.reason}
          onChange={(event) => onReason(event.target.value)}
          onBlur={onReasonDone}
        />
      </div>
    </div>
  );
}

/**
 * The form view's field for a Yes/No cell with a reason: a choice is kept at once; the reason when the person leaves its
 * box (one step of the history, like a text box). A refusal is shown in its words and the box keeps what was typed.
 */
export function ChoiceField({
  prompt,
  value,
  required,
  commit,
  serverProblems,
}: {
  prompt: string;
  value: FillValue | undefined;
  required: boolean;
  commit(choice: FillChoice): string | null;
  serverProblems: readonly string[];
}) {
  const [draft, setDraft] = useState<Draft>(() => draftOf(value));
  const [refused, setRefused] = useState<string | null>(null);
  const words = [...(refused ? [refused] : []), ...serverProblems];
  return (
    <div className="field" data-testid="choice-field">
      <ChoiceControls
        prompt={prompt}
        draft={draft}
        required={required}
        onAnswer={(answer) => {
          const next = { ...draft, answer };
          setDraft(next);
          setRefused(commit(choiceOf(next)));
        }}
        onReason={(reason) => setDraft({ ...draft, reason })}
        onReasonDone={() => setRefused(commit(choiceOf(draft)))}
      />
      {words.map((word) => (
        <p key={word} className="field-error" role="alert">
          {word}
        </p>
      ))}
    </div>
  );
}

/** The popup the grid opens on a Yes/No cell with a reason (Enter, a double click): the same controls, then Apply. */
export function ChoiceDialog({
  prompt,
  value,
  required,
  readOnly,
  onApply,
  onClose,
}: {
  prompt: string;
  value: FillValue | undefined;
  required: boolean;
  readOnly: boolean;
  onApply(choice: FillChoice): string | null;
  onClose(): void;
}) {
  const [draft, setDraft] = useState<Draft>(() => draftOf(value));
  const [refused, setRefused] = useState<string | null>(null);
  return (
    <Modal title="Yes/No with a reason" onClose={onClose} testId="choice-dialog">
      <form
        className="form stack"
        onSubmit={(event) => {
          event.preventDefault();
          const problem = onApply(choiceOf(draft));
          if (problem) {
            setRefused(problem);
            return;
          }
          onClose();
        }}
      >
        {readOnly ? (
          <p>
            {prompt ? <strong>{prompt} </strong> : null}
            {draft.answer === "yes" ? "Yes" : draft.answer === "no" ? "No" : "No answer yet"}
            {draft.reason ? `: ${draft.reason}` : ""}
          </p>
        ) : (
          <ChoiceControls
            prompt={prompt}
            draft={draft}
            required={required}
            onAnswer={(answer) => setDraft({ ...draft, answer })}
            onReason={(reason) => setDraft({ ...draft, reason })}
          />
        )}
        {refused ? (
          <p className="form-message" role="alert">
            {refused}
          </p>
        ) : null}
        <div className="split">
          {readOnly ? null : (
            <button type="submit" className="button">
              Apply
            </button>
          )}
          <button type="button" className="button button-secondary" onClick={onClose}>
            {readOnly ? "Close" : "Cancel"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
