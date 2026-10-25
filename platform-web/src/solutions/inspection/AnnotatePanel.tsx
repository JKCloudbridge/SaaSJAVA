"use client";

import { useEffect, useId, useRef, useState } from "react";
import type { AnnotateContext, Place } from "@/lib/sheets/annotate";
import { createChangeRequest, type ChangeRequestsView, type NewChangeComment } from "./changeRequests";

interface Drafted {
  place: Place | null;
  words: string;
  text: string;
}

const MOST_COMMENTS = 10;

/** The target of the comment being written: one of the places the active cell stands on, or the whole template. */
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
  title: string;
  setTitle(title: string): void;
  /** Something has been written that was not sent. */
  dirty: boolean;
  clear(): void;
}

export function useAnnotateDraft(): AnnotateDraft {
  const [target, setTarget] = useState<Target>("cell");
  const [text, setText] = useState("");
  const [drafted, setDrafted] = useState<Drafted[]>([]);
  const [title, setTitle] = useState("");
  return {
    target,
    setTarget,
    text,
    setText,
    drafted,
    setDrafted,
    title,
    setTitle,
    dirty: text.trim() !== "" || title.trim() !== "" || drafted.length > 0,
    clear: () => {
      setTarget("cell");
      setText("");
      setDrafted(() => []);
      setTitle("");
    },
  };
}

/**
 * The content of the "Ask for a change" popup (Functional track FA5, ADR-0191; a popup since FA6D). The person chose a
 * place in the grid before opening it; here they choose what the comment is about (that cell, its row, its column or the
 * whole template), write it, add it to the request they are making and send the request with a title. Nothing here
 * decides: the server checks that the person may read the template, that the version is one that is or was in use and
 * that every place is in it, and it answers in its own words, which are shown with the place that is wrong.
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
  const { target, setTarget, text, setText, drafted, setDrafted, title, setTitle, clear } = draft ?? own;
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [problems, setProblems] = useState<string[]>([]);

  // The popup opens on the box for the comment. In a microtask so that it runs after the popup has put focus on its own first control.
  useEffect(() => {
    void Promise.resolve().then(() => box.current?.focus());
  }, []);

  if (view && !view.canRequest) {
    return (
      <p className="hint" role="note" data-testid="annotate-unavailable">
        Nobody can ask for a change to this template right now.
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
      setMessage({ text: "Write the comment first.", error: true });
      return;
    }
    if (drafted.length >= MOST_COMMENTS) {
      setMessage({ text: `A request starts with at most ${MOST_COMMENTS} comments. Send it, then add more to it.`, error: true });
      return;
    }
    setDrafted((now) => [...now, { place: chosenPlace, words: chosenWords, text: words }]);
    setText("");
    setMessage(null);
    setProblems([]);
  };

  const send = async () => {
    if (title.trim() === "") {
      setMessage({ text: "Say what the request is about, in the title.", error: true });
      return;
    }
    if (drafted.length === 0) {
      setMessage({ text: "Add at least one comment to the request.", error: true });
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
    const outcome = await createChangeRequest(templateId, { title: title.trim(), versionNo: context.versionNo, comments });
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
    { which: "cell", label: "Comment on this cell" },
    { which: "row", label: "Comment on this row" },
    { which: "column", label: "Comment on this column" },
    { which: "template", label: "Comment on the whole template" },
  ];

  return (
    <div className="stack" data-testid="annotate-panel">
      <p className="hint">
        You cannot change this version. Say what you would change and send it as a request. The people who look after the
        template see it, and nobody else.
      </p>
      <div className="split" role="group" aria-label="What the comment is about">
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
        <label htmlFor={`${ids}-text`}>Your comment — {chosenWords}</label>
        <textarea id={`${ids}-text`} ref={box} rows={3} value={text} onChange={(event) => setText(event.target.value)} />
      </div>
      <div>
        <button type="button" className="button button-secondary" onClick={add}>
          Add to my request
        </button>
      </div>

      {drafted.length > 0 ? (
        <ul aria-label="Comments in my request" className="stack" data-testid="drafted">
          {drafted.map((one, index) => (
            <li key={`${one.words}-${index}`}>
              <strong>{one.words}</strong>: <span className="comment-text">{one.text}</span>{" "}
              <button
                type="button"
                className="link-button"
                onClick={() => setDrafted((now) => now.filter((_, at) => at !== index))}
              >
                Remove<span className="visually-hidden"> the comment on {one.words}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      <div className="field">
        <label htmlFor={`${ids}-title`}>What is the request about?</label>
        <input id={`${ids}-title`} type="text" value={title} onChange={(event) => setTitle(event.target.value)} />
      </div>
      <div>
        <button type="button" className="button" onClick={() => void send()} disabled={sending}>
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
