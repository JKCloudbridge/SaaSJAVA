"use client";

import { useId, useState } from "react";
import { Tag } from "@/components/ui/lists/Tag";
import { formatInstant } from "@/lib/records/fieldValue";
import {
  addChangeComment,
  answerChangeRequest,
  becameWords,
  placeOf,
  placeWords,
  statusWords,
  turnIntoVersion,
  type Answer,
  type ChangeRequestView,
  type ChangeRequestsState,
} from "./changeRequests";
import type { Place } from "@/lib/sheets/annotate";

const FILTERS = [
  { value: "", label: "All" },
  { value: "OPEN", label: "Open" },
  { value: "ACCEPTED", label: "Accepted" },
  { value: "DECLINED", label: "Declined" },
  { value: "DONE", label: "Done" },
];

interface Notice {
  text: string;
  error: boolean;
}

/**
 * The change requests of a template (Functional track FA5, ADR-0191): the requests the member may see with their comments,
 * where each comment points and whether that place is still in the version in use, and, for a person who may change the
 * template, accepting, declining with a reason, reopening and turning requests into the next version. It draws what the API
 * answers and decides nothing: the buttons follow the flags of each request, and a refusal is shown in the API's words.
 */
export function ChangeRequestsSection({
  templateId,
  state,
  onChanged,
  onShow,
}: {
  templateId: string;
  state: ChangeRequestsState;
  onChanged(): void;
  /** Stands the grid on the place of a comment (the sheet under this section), when it can. */
  onShow?(place: Place): void;
}) {
  const [filter, setFilter] = useState("");
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const filterId = useId();

  if (state.status === "loading") {
    return (
      <p role="status" className="hint">
        Loading the change requests…
      </p>
    );
  }
  if (state.status === "failed") {
    return (
      <p role="alert" className="form-message">
        {state.failure.message}
      </p>
    );
  }
  const { view } = state;
  const shown = view.requests.filter((request) => filter === "" || request.status === filter);

  const run = async (act: () => Promise<{ ok: true; text: string } | { ok: false; text: string }>) => {
    setBusy(true);
    setNotice(null);
    const done = await act();
    setBusy(false);
    setNotice({ text: done.text, error: !done.ok });
    if (done.ok) {
      onChanged();
    }
  };

  const toggle = (id: string) =>
    setPicked((now) => {
      const next = new Set(now);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });

  const turn = () =>
    run(async () => {
      const outcome = await turnIntoVersion(templateId, [...picked]);
      if (!outcome.ok) {
        return { ok: false as const, text: outcome.failure.message };
      }
      setPicked(new Set());
      return {
        ok: true as const,
        text: outcome.data.created
          ? `Version ${outcome.data.versionNo} was started as a copy of the version in use, and ${outcome.data.requests} request${outcome.data.requests === 1 ? " is" : "s are"} linked to it. Edit it in the sheet below, then send it for approval like any other version.`
          : `A draft already existed, so ${outcome.data.requests} request${outcome.data.requests === 1 ? " is" : "s are"} linked to version ${outcome.data.versionNo} as it is. Edit it in the sheet below.`,
      };
    });

  return (
    <div className="stack" data-testid="change-requests">
      {notice ? (
        <p className={notice.error ? "form-message" : "hint"} role={notice.error ? "alert" : "status"}>
          {notice.text}
        </p>
      ) : null}
      {view.requests.length === 0 ? (
        <p className="empty-state" role="status">
          {view.canReview
            ? "Nobody has asked for a change to this template."
            : "You have not asked for a change to this template. To ask for one, stand on a place in the sheet and write a comment under it."}
        </p>
      ) : (
        <>
          <div className="split">
            <div className="field">
              <label htmlFor={filterId}>Show</label>
              <select id={filterId} value={filter} onChange={(event) => setFilter(event.target.value)}>
                {FILTERS.map((one) => (
                  <option key={one.value} value={one.value}>
                    {one.label}
                  </option>
                ))}
              </select>
            </div>
            {view.canReview ? (
              <div>
                <button type="button" className="button" disabled={busy || picked.size === 0} onClick={() => void turn()}>
                  Turn the chosen requests into a new version
                </button>
              </div>
            ) : null}
          </div>
          {view.canReview ? (
            <p className="hint">
              Choose the open or accepted requests to include, then turn them into a new version. The new version is a draft
              that starts as a copy of the version in use (version {view.activeVersion}), so nothing already approved is lost.
              It goes through approval like any other.
            </p>
          ) : null}
          {shown.length === 0 ? <p className="empty-state">No request matches.</p> : null}
          <ul className="stack" aria-label="Change requests">
            {shown.map((request) => (
              <li key={request.id}>
                <RequestCard
                  templateId={templateId}
                  request={request}
                  activeVersion={view.activeVersion}
                  canReview={view.canReview}
                  chosen={picked.has(request.id)}
                  busy={busy}
                  onChoose={() => toggle(request.id)}
                  run={run}
                  onShow={onShow}
                />
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

type Run = (act: () => Promise<{ ok: true; text: string } | { ok: false; text: string }>) => Promise<void>;

function RequestCard({
  templateId,
  request,
  activeVersion,
  canReview,
  chosen,
  busy,
  onChoose,
  run,
  onShow,
}: {
  templateId: string;
  request: ChangeRequestView;
  activeVersion: number;
  canReview: boolean;
  chosen: boolean;
  busy: boolean;
  onChoose(): void;
  run: Run;
  onShow?(place: Place): void;
}) {
  const ids = useId();
  const [reply, setReply] = useState("");
  const [reason, setReason] = useState<string | null>(null);
  const tag = statusWords(request.status);
  const became = becameWords(request);
  const gone = request.placesTotal - request.placesInActive;

  const answer = (action: Answer, note: string | undefined, done: string) =>
    run(async () => {
      const outcome = await answerChangeRequest(templateId, request.id, action, note);
      return outcome.ok ? { ok: true as const, text: done } : { ok: false as const, text: outcome.failure.message };
    });

  const comment = () =>
    run(async () => {
      const outcome = await addChangeComment(templateId, request.id, { text: reply });
      if (!outcome.ok) {
        return { ok: false as const, text: outcome.failure.message };
      }
      setReply("");
      return { ok: true as const, text: "Your comment was added." };
    });

  return (
    <article className="card stack" aria-labelledby={`${ids}-title`} data-testid="change-request">
      <header className="split">
        {canReview && request.canTurn ? (
          <label className="sheet-check">
            <input type="checkbox" checked={chosen} onChange={onChoose} />
            <span className="visually-hidden">Include “{request.title}” in the new version</span>
          </label>
        ) : null}
        <h4 id={`${ids}-title`}>{request.title}</h4>
        <Tag tone={tag.tone}>{tag.text}</Tag>
      </header>
      <p className="hint">
        {request.mine ? "You asked" : `${request.requesterName ?? "Someone who has left"} asked`} on{" "}
        {formatInstant(request.createdAt)}, reading version {request.versionNo}
        {request.versionNo !== activeVersion && activeVersion > 0 ? `; version ${activeVersion} is in use now` : ""}.
        {request.placesTotal > 0
          ? ` ${request.placesTotal} place${request.placesTotal === 1 ? "" : "s"} named${gone > 0 ? `, ${gone} no longer in the version in use` : ""}.`
          : ""}
      </p>
      {became ? <p>Turned into {became}.</p> : null}
      {request.decisionNote ? (
        <p>
          <strong>Reason it was declined:</strong> <span className="comment-text">{request.decisionNote}</span>
        </p>
      ) : null}

      <ul className="stack" aria-label="Comments">
        {request.comments.map((comment) => {
          const place = placeOf(comment);
          return (
            <li key={comment.id}>
              <strong>{placeWords(comment)}</strong>
              {place && !comment.inActiveVersion ? <Tag tone="error">no longer in this version</Tag> : null}
              {place && comment.inActiveVersion && onShow ? (
                <>
                  {" "}
                  <button type="button" className="link-button" onClick={() => onShow(place)}>
                    Show in the sheet<span className="visually-hidden">: {placeWords(comment)}</span>
                  </button>
                </>
              ) : null}
              <br />
              <span className="comment-text">{comment.text}</span>
              <br />
              <span className="hint">
                {comment.mine ? "You" : (comment.authorName ?? "Someone who has left")}, {formatInstant(comment.createdAt)}
              </span>
            </li>
          );
        })}
      </ul>

      {request.canComment ? (
        <div className="field">
          <label htmlFor={`${ids}-reply`}>Add a comment</label>
          <textarea id={`${ids}-reply`} rows={2} value={reply} onChange={(event) => setReply(event.target.value)} />
          <div>
            <button type="button" className="button button-secondary" disabled={busy || reply.trim() === ""} onClick={() => void comment()}>
              Add comment
            </button>
          </div>
        </div>
      ) : null}

      {request.canAccept || request.canDecline || request.canReopen ? (
        <div className="split">
          {request.canAccept ? (
            <button
              type="button"
              className="button button-secondary"
              disabled={busy}
              onClick={() => void answer("ACCEPT", undefined, "The request was accepted.")}
            >
              Accept
            </button>
          ) : null}
          {request.canDecline ? (
            <button type="button" className="button button-danger" disabled={busy} onClick={() => setReason(reason === null ? "" : null)}>
              Decline
            </button>
          ) : null}
          {request.canReopen ? (
            <button
              type="button"
              className="button button-secondary"
              disabled={busy}
              onClick={() => void answer("REOPEN", undefined, "The request is open again.")}
            >
              Reopen
            </button>
          ) : null}
        </div>
      ) : null}
      {reason !== null ? (
        <div className="field" role="group" aria-label="Decline this request">
          <label htmlFor={`${ids}-reason`}>Why is it declined? The person who asked will read this.</label>
          <textarea id={`${ids}-reason`} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} />
          <div>
            <button
              type="button"
              className="button button-danger"
              disabled={busy || reason.trim() === ""}
              onClick={() => void answer("DECLINE", reason, "The request was declined.").then(() => setReason(null))}
            >
              Decline it
            </button>
          </div>
        </div>
      ) : null}
    </article>
  );
}
