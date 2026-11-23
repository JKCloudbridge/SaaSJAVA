"use client";

import { useCallback, useId, useState } from "react";
import { api } from "@/lib/api/client";
import type { Choice } from "@/lib/approvals/choiceForm";
import { call } from "@/lib/records/call";
import { choiceFormElement, choiceNeeded } from "@/solutions/choices";
import { PersonPicker } from "./PersonPicker";

/** What a person can do with a request; the server says which of these are open to them (`actions`). */
export type ApprovalActionKind = "approve" | "reject" | "reassign" | "assign" | "recall" | "cancel";

/**
 * What a page may do before a decision is sent (SH5.1): save what the person typed on the same page, so that the decision
 * judges what they see. It says whether that worked; when it did not, `message` says why in words to show, and the
 * decision is not sent.
 */
export type BeforeDecision = () => Promise<{ ok: boolean; message?: string }>;

/** The decisions that end or withdraw a request: the page saves first. Handing a task on or ending a request asks nothing of the cells. */
const SAVES_FIRST: ReadonlySet<ApprovalActionKind> = new Set(["approve", "reject", "recall"]);

/** What each action asks of the person: a comment (required or not) and, for a hand-over, a person. */
const ASKS: Record<ApprovalActionKind, { comment: "required" | "optional"; person: boolean; button: string; danger: boolean }> = {
  approve: { comment: "optional", person: false, button: "Approve", danger: false },
  reject: { comment: "required", person: false, button: "Reject", danger: true },
  reassign: { comment: "optional", person: true, button: "Hand it on", danger: false },
  assign: { comment: "optional", person: true, button: "Ask this person as well", danger: false },
  recall: { comment: "optional", person: false, button: "Withdraw the request", danger: true },
  cancel: { comment: "optional", person: false, button: "End the request", danger: true },
};

/**
 * The confirmation of one action on an approval request (Sprint 24, ADR-0147): it says exactly what will happen, takes the
 * comment (required to reject) and, to hand a task on, the person, and then asks the API. The page decides nothing: the
 * version of the request it read goes with the call, and the API's own words come back for a refusal (a request someone
 * else decided first, a person who cannot be asked). The comment is text of a person; it is sent and never kept here.
 *
 * @param explain a sentence saying what will happen if the person goes on, written by the screen from what it shows
 * @param asksForChoice whether the active step needs a choice with the approval (the process says so, FA6G); the form of
 *        the object's module is shown and the approval is not sent without what it gives. The server insists as well
 * @param lastStep whether the active step is the last of the process; the last approval of some objects asks for a choice
 *        of its own (FA8)
 * @param objectApiName the object of the record, to find the form that asks for the choice
 * @param recordId the record the approval is about
 * @param beforeDecision what the page does first for an approval, a rejection or a withdrawal (it saves the cells typed on
 *        the same page); nothing is sent when it says no, and its words are shown
 */
export function ApprovalActionForm({
  kind,
  requestId,
  version,
  explain,
  labels,
  asksForChoice = false,
  lastStep = false,
  objectApiName,
  recordId,
  beforeDecision,
  onDone,
  onClose,
}: {
  kind: ApprovalActionKind;
  requestId: string;
  version: number;
  explain: string;
  /** Other words for the button of an action (a change request is accepted or declined, not approved or rejected). */
  labels?: Partial<Record<ApprovalActionKind, string>>;
  asksForChoice?: boolean;
  lastStep?: boolean;
  objectApiName?: string;
  recordId?: string;
  beforeDecision?: BeforeDecision;
  onDone: (text: string) => void;
  onClose: () => void;
}) {
  const ask = { ...ASKS[kind], button: labels?.[kind] ?? ASKS[kind].button };
  const id = useId();
  const [comment, setComment] = useState("");
  const [person, setPerson] = useState("");
  const [personName, setPersonName] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>();
  const [choices, setChoices] = useState<Choice[] | null>(null);
  const onChoices = useCallback((next: Choice[] | null) => setChoices(next), []);
  // Only an approval carries the choice; rejecting at the same step asks for none.
  const needsChoice = kind === "approve" && choiceNeeded(objectApiName, asksForChoice, lastStep);
  const choiceForm =
    needsChoice && objectApiName && recordId
      ? choiceFormElement(objectApiName, { recordId, onChange: onChoices, lastStep })
      : undefined;
  const missing =
    (ask.comment === "required" && comment.trim() === "") ||
    (ask.person && person === "") ||
    (needsChoice && (choiceForm === undefined || choices === null));

  async function go() {
    setBusy(true);
    setFailure(undefined);
    if (beforeDecision && SAVES_FIRST.has(kind)) {
      const first = await beforeDecision();
      if (!first.ok) {
        setBusy(false);
        setFailure(first.message ?? "What you typed could not be saved, so nothing was decided.");
        return;
      }
    }
    const path = { params: { path: { id: requestId } } };
    const note = comment.trim() === "" ? undefined : comment.trim();
    const outcome = await call(() => {
      switch (kind) {
        case "approve":
          return api.POST("/api/v1/approvals/instances/{id}/approve", {
            ...path,
            body: { version, comment: note, choices: needsChoice ? (choices ?? undefined) : undefined },
          });
        case "reject":
          return api.POST("/api/v1/approvals/instances/{id}/reject", { ...path, body: { version, comment: note } });
        case "recall":
          return api.POST("/api/v1/approvals/instances/{id}/recall", { ...path, body: { version, comment: note } });
        case "cancel":
          return api.POST("/api/v1/approvals/instances/{id}/cancel", { ...path, body: { version, comment: note } });
        case "reassign":
          return api.POST("/api/v1/approvals/instances/{id}/reassign", {
            ...path,
            body: { toUserId: person, version, comment: note },
          });
        case "assign":
          return api.POST("/api/v1/approvals/instances/{id}/assign", {
            ...path,
            body: { toUserId: person, version, comment: note },
          });
      }
    });
    setBusy(false);
    if (outcome.ok) {
      onDone(`${ask.button}: done.`);
    } else {
      setFailure(outcome.failure.message);
    }
  }

  return (
    <form
      className="stack card"
      aria-label={ask.button}
      data-testid={`approval-action-${kind}`}
      onSubmit={(event) => {
        event.preventDefault();
        if (!missing) {
          void go();
        }
      }}
    >
      {explain ? <p data-testid="approval-explain">{explain}</p> : null}
      {choiceForm}
      {needsChoice && !choiceForm ? (
        <p className="form-message" role="alert" data-testid="approval-choice-unavailable">
          This step needs a choice that this screen cannot ask for. Open the record to approve it.
        </p>
      ) : null}
      {ask.person ? (
        <PersonPicker id={`${id}-person`} label="Who" value={person} name={personName} onChange={(value, name) => {
          setPerson(value);
          setPersonName(name);
        }} />
      ) : null}
      <div className="field">
        <label htmlFor={`${id}-comment`}>
          Comment{ask.comment === "required" ? " (required)" : " (optional)"}
        </label>
        <textarea
          id={`${id}-comment`}
          rows={3}
          maxLength={2000}
          value={comment}
          aria-required={ask.comment === "required"}
          onChange={(event) => setComment(event.target.value)}
        />
        <p className="hint">Kept in the history of the request and shown to those who may see it.</p>
      </div>
      {failure ? (
        <p className="form-message" role="alert" data-testid="approval-action-failed">
          {failure}
        </p>
      ) : null}
      <div className="filters-actions">
        <button type="submit" className={ask.danger ? "button button-danger" : "button"} disabled={busy || missing}>
          {ask.button}
        </button>
        <button type="button" className="link-button" disabled={busy} onClick={onClose}>
          Keep it as it is
        </button>
      </div>
    </form>
  );
}
