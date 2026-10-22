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
 * Asking for a change (Functional track FA5, ADR-0191), drawn under the grid of the version in use. A person stands on a
 * place in the grid (a cell, with its row and column), chooses what the comment is about, writes it, adds it to the request
 * they are making and sends the request with a title. Nothing here decides: the server checks that the person may read the
 * template, that the version is one that is or was in use and that every place is in it, and it answers in its own words,
 * which are shown with the place that is wrong. The box for a comment takes focus when the person presses Enter on a cell.
 */
export function AnnotatePanel({
  templateId,
  context,
  view,
  show,
  onShown,
  onSent,
}: {
  templateId: string;
  context: AnnotateContext;
  view: ChangeRequestsView | undefined;
  /** A place a comment in the list asked to be shown: the grid is stood on it, then `onShown` is called. */
  show: Place | null;
  onShown(): void;
  onSent(): void;
}) {
  const ids = useId();
  const box = useRef<HTMLTextAreaElement>(null);
  const [target, setTarget] = useState<Target>("cell");
  const [text, setText] = useState("");
  const [drafted, setDrafted] = useState<Drafted[]>([]);
  const [title, setTitle] = useState("");
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [problems, setProblems] = useState<string[]>([]);

  // Enter on a cell of the grid: stand the comment on that cell and move to the box.
  const seen = useRef(context.activations);
  useEffect(() => {
    if (context.activations === seen.current) {
      return;
    }
    seen.current = context.activations;
    // In a microtask, like the other screens that react to the page around them, so that no state is set while the effect runs.
    void Promise.resolve().then(() => {
      setTarget(context.places.cell ? "cell" : "column");
      box.current?.focus();
    });
  }, [context.activations, context.places.cell]);

  const { select } = context;
  useEffect(() => {
    if (show) {
      // In a microtask, like the other screens that react to the page around them, so that no state is set while the effect runs.
      void Promise.resolve().then(() => {
        select(show);
        onShown();
      });
    }
    // `select` is a new function on every render of the grid; only a place asked for should run this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [show]);

  if (view && !view.canRequest) {
    return (
      <p className="hint" role="note" data-testid="annotate-unavailable">
        Nobody can ask for a change to this template right now.
      </p>
    );
  }

  const placeOfTarget = (which: Target): Place | null => (which === "template" ? null : context.places[which]);
  const available = (which: Target): boolean => which === "template" || context.places[which] !== null;
  const chosen: Target = available(target) ? target : "template";
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
    setDrafted([]);
    setTitle("");
    setText("");
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
    <section className="card stack" aria-label="Ask for a change" data-testid="annotate-panel">
      <h3>Ask for a change</h3>
      <p className="hint">
        You cannot change this version. Stand on a place in the grid, say what you would change, and send it as a request. The
        people who look after the template see it, and nobody else.
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
    </section>
  );
}
