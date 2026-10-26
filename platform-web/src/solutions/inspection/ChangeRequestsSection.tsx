"use client";

import Link from "next/link";
import { useId, useState } from "react";
import { Tag } from "@/components/ui/lists/Tag";
import { formatInstant } from "@/lib/records/fieldValue";
import {
  addChangeComment,
  becameWords,
  changeRequestsHref,
  placeOf,
  placeWords,
  statusWords,
  type ChangeRequestView,
  type ChangeRequestsState,
} from "./changeRequests";
import type { Place } from "@/lib/sheets/annotate";

const FILTERS = [
  { value: "", label: "All" },
  { value: "OPEN", label: "In progress" },
  { value: "DECLINED", label: "Declined" },
  { value: "DONE", label: "Published" },
];

interface Notice {
  text: string;
  error: boolean;
}

/**
 * The change requests of a template under its sheet (Functional track FA5, ADR-0191; FA8, ADR-0202): the requests the member
 * may see, the places each points at and whether that place is still in the revision in use, with a way to the request's own
 * page where the register, the revised sheet and the approval are. A request is decided by its approval and published by the
 * last step, so there is nothing to accept, turn or reopen here. It draws what the API answers and decides nothing.
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
  /** Stands the grid on the place of a note (the sheet under this section), when it can. */
  onShow?(place: Place): void;
}) {
  const [filter, setFilter] = useState("");
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
            : "You have not asked for a change to this template. To ask for one, press Ask for a change."}
        </p>
      ) : (
        <>
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
          {shown.length === 0 ? <p className="empty-state">No request matches.</p> : null}
          <ul className="stack" aria-label="Change requests">
            {shown.map((request) => (
              <li key={request.id}>
                <RequestCard
                  templateId={templateId}
                  request={request}
                  busy={busy}
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
  busy,
  run,
  onShow,
}: {
  templateId: string;
  request: ChangeRequestView;
  busy: boolean;
  run: Run;
  onShow?(place: Place): void;
}) {
  const ids = useId();
  const [reply, setReply] = useState("");
  const tag = statusWords(request.status);
  const became = becameWords(request);
  const gone = request.placesTotal - request.placesInActive;

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
        <h4 id={`${ids}-title`}>{request.title}</h4>
        <Tag tone={tag.tone}>{tag.text}</Tag>
      </header>
      <p className="hint">
        {request.mine ? "You asked" : `${request.requesterName ?? "Someone who has left"} asked`} on{" "}
        {formatInstant(request.createdAt)}.
        {request.placesTotal > 0
          ? ` ${request.placesTotal} place${request.placesTotal === 1 ? "" : "s"} named${gone > 0 ? `, ${gone} no longer in the revision in use` : ""}.`
          : ""}
      </p>
      <p>
        <Link href={changeRequestsHref(templateId, request.id)} className="link-button" data-testid="request-open">
          Open the request
        </Link>
        {request.reviewId && request.status === "OPEN" ? <span className="hint"> It is waiting for its approval.</span> : null}
      </p>
      <p className="hint" data-testid="request-department">
        Department when asked: {request.requesterDepartment ?? "—"}
      </p>
      {became ? <p>{became}.</p> : null}

      {request.comments.length > 0 ? (
        <ul className="stack" aria-label="Places">
          {request.comments.map((item) => {
            const place = placeOf(item);
            return (
              <li key={item.id}>
                <strong>{placeWords(item)}</strong>
                {place && !item.inActiveVersion ? <Tag tone="error">no longer in this revision</Tag> : null}
                {place && item.inActiveVersion && onShow ? (
                  <>
                    {" "}
                    <button type="button" className="link-button" onClick={() => onShow(place)}>
                      Show in the sheet<span className="visually-hidden">: {placeWords(item)}</span>
                    </button>
                  </>
                ) : null}
                <br />
                <span className="comment-text">{item.text}</span>
                <br />
                <span className="hint">
                  {item.mine ? "You" : (item.authorName ?? "Someone who has left")}, {formatInstant(item.createdAt)}
                </span>
              </li>
            );
          })}
        </ul>
      ) : null}

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
    </article>
  );
}
