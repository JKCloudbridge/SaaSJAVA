"use client";

import { useId, useRef, useState } from "react";
import type { AnnotateContext, Place } from "@/lib/sheets/annotate";
import { createChangeRequest, type ChangeRequestsView, type NewChangeComment } from "./changeRequests";
import { RegisterRaisePanel } from "./RegisterPanel";
import { EMPTY_REGISTER_INPUT, type RegisterInput } from "./register";

interface Drafted {
  place: Place | null;
  words: string;
  text: string;
}

const MOST_COMMENTS = 10;

/** The target of the note being written: one of the places the active cell stands on, or the whole template. */
type Target = "cell" | "row" | "column" | "template";

/**
 * What a person has written and not yet sent. It lives outside the popup (in {@link AskForChange}) so that closing the
 * popup does not throw it away; the panel works on whatever it is given.
 */
export interface AnnotateDraft {
  target: Target;
  setTarget(target: Target): void;
  text: string;
  setText(text: string): void;
  drafted: Drafted[];
  setDrafted(update: (now: Drafted[]) => Drafted[]): void;
  /** The boxes of the register form that the person types. */
  register: RegisterInput;
  setRegister(register: RegisterInput): void;
  /** Something has been written that was not sent. */
  dirty: boolean;
  clear(): void;
}

const typed = (register: RegisterInput): boolean =>
  [
    register.requestNo,
    register.changeRequested,
    register.basisForChange,
    register.horizontalDeployment,
    register.obsoletedFormat,
    register.obsoletedEffectiveOn,
  ].some((box) => (box ?? "").trim() !== "");

export function useAnnotateDraft(): AnnotateDraft {
  const [target, setTarget] = useState<Target>("cell");
  const [text, setText] = useState("");
  const [drafted, setDrafted] = useState<Drafted[]>([]);
  const [register, setRegister] = useState<RegisterInput>(EMPTY_REGISTER_INPUT);
  return {
    target,
    setTarget,
    text,
    setText,
    drafted,
    setDrafted,
    register,
    setRegister,
    dirty: text.trim() !== "" || typed(register) || drafted.length > 0,
    clear: () => {
      setTarget("cell");
      setText("");
      setDrafted(() => []);
      setRegister(EMPTY_REGISTER_INPUT);
    },
  };
}

/**
 * The content of the "Ask for a change" popup (Functional track FA5, ADR-0191; a popup since FA6D; the register form since
 * FA8, ADR-0202). The person fills in the register (as a form, or in Excel and uploaded), may point at the places on the
 * sheet they mean, and sends the request. Nothing here decides: the server checks that the person may read the template,
 * that the revision is one that is or was in use, that no other change is in progress and that every box and place is
 * valid, and it answers in its own words, which are shown with the place that is wrong.
 */
export function AnnotatePanel({
  templateId,
  context,
  view,
  draft,
  onSent,
}: {
  templateId: string;
  context: AnnotateContext;
  view: ChangeRequestsView | undefined;
  /** The words kept outside the popup; without it the panel keeps its own (it is lost when the panel goes). */
  draft?: AnnotateDraft;
  onSent(): void;
}) {
  const ids = useId();
  const box = useRef<HTMLTextAreaElement>(null);
  const own = useAnnotateDraft();
  const { target, setTarget, text, setText, drafted, setDrafted, register, setRegister, clear } = draft ?? own;
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [problems, setProblems] = useState<string[]>([]);

  if (view && !view.canRequest) {
    return (
      <p className="hint" role="note" data-testid="annotate-unavailable">
        {view.inProgress
          ? "A change is already in progress on this template. You can ask for another when it has been published or declined."
          : "Nobody can ask for a change to this template right now."}
      </p>
    );
  }

  const placeOfTarget = (which: Target): Place | null => (which === "template" ? null : context.places[which]);
  const available = (which: Target): boolean => which === "template" || context.places[which] !== null;
  const chosen: Target = available(target) ? target : available("column") ? "column" : "template";
  const chosenPlace = placeOfTarget(chosen);
  const chosenWords = chosenPlace ? context.describe(chosenPlace) : "The whole template";

  const add = () => {
    const words = text.trim();
    if (words === "") {
      setMessage({ text: "Write the note first.", error: true });
      return;
    }
    if (drafted.length >= MOST_COMMENTS) {
      setMessage({ text: `A request starts with at most ${MOST_COMMENTS} places. Send it, then add more to it.`, error: true });
      return;
    }
    setDrafted((now) => [...now, { place: chosenPlace, words: chosenWords, text: words }]);
    setText("");
    setMessage(null);
    setProblems([]);
  };

  const send = async () => {
    if (register.requestNo.trim() === "") {
      setMessage({ text: "Write the request number.", error: true });
      return;
    }
    if (register.changeRequested.trim() === "") {
      setMessage({ text: "Write what change you ask for.", error: true });
      return;
    }
    setSending(true);
    setMessage(null);
    setProblems([]);
    const comments: NewChangeComment[] = drafted.map((one) => ({
      rowId: one.place?.rowId ?? undefined,
      columnId: one.place?.columnId ?? undefined,
      text: one.text,
    }));
    const outcome = await createChangeRequest(templateId, { versionNo: context.versionNo, register, comments });
    setSending(false);
    if (!outcome.ok) {
      setMessage({ text: outcome.failure.message, error: true });
      setProblems(Object.values(outcome.failure.fields ?? {}).flat());
      return;
    }
    clear();
    setMessage({ text: "Your request was sent to the people who look after this template.", error: false });
    onSent();
  };

  const choices: { which: Target; label: string }[] = [
    { which: "cell", label: "This cell" },
    { which: "row", label: "This row" },
    { which: "column", label: "This column" },
    { which: "template", label: "The whole template" },
  ];

  return (
    <div className="stack" data-testid="annotate-panel">
      <p className="hint">
        You cannot change this revision. Fill in the register and send it as a request. The people who look after the
        template decide it, and nobody else sees it.
      </p>
      <RegisterRaisePanel templateId={templateId} value={register} onChange={setRegister} />

      <fieldset className="stack">
        <legend>Where on the sheet (optional)</legend>
        <div className="split" role="group" aria-label="What the note is about">
          {choices.map((choice) => (
            <button
              key={choice.which}
              type="button"
              className={chosen === choice.which ? "button" : "button button-secondary"}
              aria-pressed={chosen === choice.which}
              disabled={!available(choice.which)}
              onClick={() => {
                setTarget(choice.which);
                box.current?.focus();
              }}
            >
              {choice.label}
            </button>
          ))}
        </div>
        <div className="field">
          <label htmlFor={`${ids}-text`}>Your note — {chosenWords}</label>
          <textarea id={`${ids}-text`} ref={box} rows={2} value={text} onChange={(event) => setText(event.target.value)} />
        </div>
        <div>
          <button type="button" className="button button-secondary" onClick={add}>
            Add the place to my request
          </button>
        </div>
        {drafted.length > 0 ? (
          <ul aria-label="Places in my request" className="stack" data-testid="drafted">
            {drafted.map((one, index) => (
              <li key={`${one.words}-${index}`}>
                <strong>{one.words}</strong>: <span className="comment-text">{one.text}</span>{" "}
                <button
                  type="button"
                  className="link-button"
                  onClick={() => setDrafted((now) => now.filter((_, at) => at !== index))}
                >
                  Remove<span className="visually-hidden"> the note on {one.words}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </fieldset>

      <div>
        <button type="button" className="button" onClick={() => void send()} disabled={sending} data-testid="send-request">
          {sending ? "Sending…" : "Send the request"}
        </button>
      </div>
      {message ? (
        <p className={message.error ? "form-message" : "hint"} role={message.error ? "alert" : "status"}>
          {message.text}
        </p>
      ) : null}
      {problems.length > 0 ? (
        <ul className="form-message" role="alert">
          {problems.map((problem, index) => (
            <li key={`${problem}-${index}`}>{problem}</li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
