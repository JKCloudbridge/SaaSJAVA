"use client";

import { useId, useState } from "react";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { PersonPicker } from "./PersonPicker";

/** What a person can do with a request; the server says which of these are open to them (`actions`). */
export type ApprovalActionKind = "approve" | "reject" | "reassign" | "assign" | "recall" | "cancel";

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
 */
export function ApprovalActionForm({
  kind,
  requestId,
  version,
  explain,
  onDone,
  onClose,
}: {
  kind: ApprovalActionKind;
  requestId: string;
  version: number;
  explain: string;
  onDone: (text: string) => void;
  onClose: () => void;
}) {
  const ask = ASKS[kind];
  const id = useId();
  const [comment, setComment] = useState("");
  const [person, setPerson] = useState("");
  const [personName, setPersonName] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>();
  const missing =
    (ask.comment === "required" && comment.trim() === "") || (ask.person && person === "");

  async function go() {
    setBusy(true);
    setFailure(undefined);
    const path = { params: { path: { id: requestId } } };
    const note = comment.trim() === "" ? undefined : comment.trim();
    const outcome = await call(() => {
      switch (kind) {
        case "approve":
          return api.POST("/api/v1/approvals/instances/{id}/approve", { ...path, body: { version, comment: note } });
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
      <p data-testid="approval-explain">{explain}</p>
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
