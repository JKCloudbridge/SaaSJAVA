"use client";

import { useState } from "react";
import { createChangeRequest, type ChangeRequestsView } from "./changeRequests";
import { RegisterRaisePanel } from "./RegisterPanel";
import { EMPTY_REGISTER_INPUT, type RegisterInput } from "./register";

/**
 * What a person has written and not yet sent. It lives outside the popup (in {@link AskForChange}) so that closing the
 * popup does not throw it away; the panel works on whatever it is given.
 */
export interface RequestDraft {
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

export function useRequestDraft(): RequestDraft {
  const [register, setRegister] = useState<RegisterInput>(EMPTY_REGISTER_INPUT);
  return {
    register,
    setRegister,
    dirty: typed(register),
    clear: () => setRegister(EMPTY_REGISTER_INPUT),
  };
}

/**
 * The content of the "Ask for a change" popup (Functional track FA5, ADR-0191; a popup since FA6D; the register form since
 * FA8, ADR-0202; no place on the sheet any more since FA10, ADR-0204). The person fills in the register (as a form, or in
 * Excel and uploaded) and sends the request. Nothing here decides: the server checks that the person may read the template,
 * that the revision is one that is or was in use, that no other change is in progress and that every box is valid, and it
 * answers in its own words, which are shown with the place that is wrong.
 */
export function RequestPanel({
  templateId,
  versionNo,
  view,
  draft,
  onSent,
}: {
  templateId: string;
  /** The revision in use that the request is about. */
  versionNo: number;
  view: ChangeRequestsView | undefined;
  /** The words kept outside the popup; without it the panel keeps its own (it is lost when the panel goes). */
  draft?: RequestDraft;
  onSent(): void;
}) {
  const own = useRequestDraft();
  const { register, setRegister, clear } = draft ?? own;
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [problems, setProblems] = useState<string[]>([]);

  if (view && !view.canRequest) {
    return (
      <p className="hint" role="note" data-testid="request-unavailable">
        {view.inProgress
          ? "A change is already in progress on this template. You can ask for another when it has been published or declined."
          : "Nobody can ask for a change to this template right now."}
      </p>
    );
  }

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
    const outcome = await createChangeRequest(templateId, { versionNo, register, comments: [] });
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

  return (
    <div className="stack" data-testid="request-panel">
      <p className="hint">
        You cannot change this revision. Fill in the register and send it as a request. The people who look after the
        template decide it, and nobody else sees it.
      </p>
      <RegisterRaisePanel templateId={templateId} value={register} onChange={setRegister} />
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
