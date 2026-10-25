"use client";

import Link from "next/link";
import { useId, useState } from "react";
import { ActionNotice } from "@/components/ui/ActionNotice";
import {
  ApprovalDialogs,
  ApprovalHeaderActions,
  useRecordApproval,
  type ApprovalState,
  type RecordApproval,
} from "@/components/records/ApprovalPanel";
import { ApprovalFlow } from "@/components/records/ApprovalFlow";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { formatInstant } from "@/lib/records/fieldValue";
import {
  addChangeComment,
  becameWords,
  placeOf,
  reopenChangeRequest,
  placeWords,
  statusWords,
  turnIntoVersion,
  useChangeRequest,
  type ChangeRequestView,
} from "./changeRequests";
import { REQUEST_OBJECT } from "./template";
import { revisionWords } from "@/lib/sheets/revision";

const LABELS = { approve: "Accept", reject: "Decline" } as const;

const templateHref = (templateId: string, tab?: string) =>
  `/apps/inspection/s-templates?open=${templateId}${tab ? `&tab=${tab}` : ""}`;

/**
 * One change request on its own page (Functional track FA6E, ADR-0199): at the top left the template (name and number) and the
 * version the person read; in one row who asked and from which department; the comment they wrote, then the changes they
 * asked for, place by place; and at the top right Accept and Decline. Accept and Decline are the buttons of the request's
 * approval (the people who have a task in it see them; declining asks for a reason), so the page never decides who may answer:
 * it draws what the API says. A request that is answered directly, because the organization has no approval process for
 * requests, has the same two buttons from the request's own flags. The template page keeps its section of requests.
 */
export function ChangeRequestPage({
  id,
  onBack,
  onChanged,
}: {
  id: string;
  onBack: () => void;
  /** Called after an answer, a comment or a turn, so a page around this one can read its own state again. */
  onChanged?: () => void;
}) {
  const [refresh, setRefresh] = useState(0);
  const { state, reload } = useChangeRequest(id, refresh);
  const changed = () => {
    setRefresh((now) => now + 1);
    reload();
    onChanged?.();
  };
  if (state.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the change request…
      </p>
    );
  }
  if (state.status === "failed") {
    return (
      <MissingDetail
        listLabel="Change requests"
        onBack={onBack}
        what="This change request is not available. It may not exist, or it may not be part of your access."
      />
    );
  }
  const request = state.request;
  return request.reviewId ? (
    <Reviewed request={request} reviewId={request.reviewId} refresh={refresh} onChanged={changed} onBack={onBack} />
  ) : (
    <Body request={request} onChanged={changed} onBack={onBack} />
  );
}

/** A request with an approval: the buttons and the flow come from the approval of its review record. */
function Reviewed({
  request,
  reviewId,
  refresh,
  onChanged,
  onBack,
}: {
  request: ChangeRequestView;
  reviewId: string;
  refresh: number;
  onChanged(): void;
  onBack(): void;
}) {
  const [, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const decisions = useRecordApproval({
    objectApiName: REQUEST_OBJECT,
    recordId: reviewId,
    refresh,
    onState: setApproval,
    onChanged,
    labels: LABELS,
  });
  return <Body request={request} onChanged={onChanged} onBack={onBack} approval={decisions} />;
}

/** The reason an approver gave for declining, from the history of the approval when the caller may read it. */
function declinedBecause(approval: RecordApproval | undefined): string | undefined {
  const current = approval?.panel?.available ? approval.panel.current : undefined;
  if (!current?.historyVisible) {
    return undefined;
  }
  return [...current.history].reverse().find((line) => line.kind === "REJECTED" && line.comment)?.comment ?? undefined;
}

function Body({
  request,
  onChanged,
  onBack,
  approval,
}: {
  request: ChangeRequestView;
  onChanged(): void;
  onBack(): void;
  approval?: RecordApproval;
}) {
  const ids = useId();
  const [notice, setNotice] = useState<{ text: string; error: boolean } | undefined>();
  const [busy, setBusy] = useState(false);
  const [reply, setReply] = useState("");
  const tag = statusWords(request.status);
  const became = becameWords(request);
  const [first, ...rest] = request.comments;
  const templateName = request.templateName ?? "Template";
  const title = request.templateNumber ? `${templateName} · ${request.templateNumber}` : templateName;
  const declined = request.status === "DECLINED" ? declinedBecause(approval) : undefined;

  const run = async (act: () => Promise<{ ok: boolean; text: string }>) => {
    setBusy(true);
    setNotice(undefined);
    const done = await act();
    setBusy(false);
    setNotice({ text: done.text, error: !done.ok });
    if (done.ok) {
      onChanged();
    }
  };
  const reopen = () =>
    run(async () => {
      const outcome = await reopenChangeRequest(request.templateId, request.id);
      return outcome.ok ? { ok: true, text: "The request is accepted again and can be turned into a revision." } : { ok: false, text: outcome.failure.message };
    });
  const turn = () =>
    run(async () => {
      const outcome = await turnIntoVersion(request.templateId, [request.id]);
      return outcome.ok
        ? {
            ok: true,
            text: `${revisionWords(outcome.data.versionNo)} ${outcome.data.created ? "was started as a copy of the revision in use" : "already existed, so this request is linked to it"}. Edit it on the Sheet tab of the template, then send it for approval like any other revision.`,
          }
        : { ok: false, text: outcome.failure.message };
    });
  const comment = () =>
    run(async () => {
      const outcome = await addChangeComment(request.templateId, request.id, { text: reply });
      if (!outcome.ok) {
        return { ok: false, text: outcome.failure.message };
      }
      setReply("");
      return { ok: true, text: "Your comment was added." };
    });

  const actions = (
    <>
      {approval ? <ApprovalHeaderActions approval={approval} submitButton={false} /> : null}
      {request.canTurn ? (
        <button type="button" className="button button-secondary" disabled={busy} onClick={() => void turn()} data-testid="request-turn">
          Turn into a new revision
        </button>
      ) : null}
      {request.canReopen ? (
        <button type="button" className="button button-secondary" disabled={busy} onClick={() => void reopen()}>
          Reopen
        </button>
      ) : null}
    </>
  );

  return (
    <DetailPage
      listLabel="Change requests"
      onBack={onBack}
      noBreadcrumb
      title={title}
      titleExtra={
        <>
          <Tag tone={tag.tone}>{tag.text}</Tag>
          <span className="hint" data-testid="request-version">
            {revisionWords(request.versionNo)} read
          </span>
        </>
      }
      actions={actions}
      notices={
        <>
          {notice ? <ActionNotice key={notice.text} text={notice.text} error={notice.error} className="hint" occurrence={notice} /> : null}
          {approval ? <ApprovalDialogs approval={approval} recordVersion={0} /> : null}
        </>
      }
      testId="change-request-page"
    >
      <div className={approval?.panel?.available && approval.panel.current ? "detail-with-flow detail-has-flow" : "detail-with-flow"}>
        <div className="stack">
          <DetailGrid>
            <DetailItem label="Change requested by">
              <span data-testid="request-by">{request.mine ? "You" : (request.requesterName ?? "Someone who has left")}</span>
            </DetailItem>
            <DetailItem label="Department">
              <span data-testid="request-department">{request.requesterDepartment ?? "—"}</span>
            </DetailItem>
            <DetailItem label="Request">{request.title}</DetailItem>
            <DetailItem label="Asked on">{formatInstant(request.createdAt)}</DetailItem>
          </DetailGrid>
          {became ? <p>Turned into {became}.</p> : null}
          {declined ? (
            <p data-testid="request-declined-reason">
              <strong>Reason it was declined:</strong> <span className="comment-text">{declined}</span>
            </p>
          ) : null}

          <DetailSection title="Comment" stack>
            {first ? (
              <div data-testid="request-first-comment">
                {placeOf(first) ? (
                  <p>
                    <strong>{placeWords(first)}</strong>
                  </p>
                ) : null}
                <span className="comment-text">{first.text}</span>
                <p className="hint">
                  {first.mine ? "You" : (first.authorName ?? "Someone who has left")}, {formatInstant(first.createdAt)}
                </p>
              </div>
            ) : (
              <p className="hint">The person who asked wrote no comment.</p>
            )}
          </DetailSection>

          <DetailSection title="Changes requested" stack>
            {rest.length === 0 ? (
              <p className="hint" data-testid="request-no-changes">
                No further places or comments.
              </p>
            ) : (
              <ul className="request-place-list" aria-label="Changes requested">
                {rest.map((item) => {
                  const place = placeOf(item);
                  return (
                    <li key={item.id}>
                      <strong>{placeWords(item)}</strong>
                      {place && !item.inActiveVersion ? <Tag tone="error">no longer in this revision</Tag> : null}
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
            )}
            <p>
              <Link href={templateHref(request.templateId, "sheet")} className="link-button">
                Open the template
              </Link>
            </p>
          </DetailSection>

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
        </div>
        {approval?.panel?.available && approval.panel.current ? (
          <aside className="detail-flow-column" aria-label="Where the approval stands">
            <ApprovalFlow approval={approval} />
          </aside>
        ) : null}
      </div>
    </DetailPage>
  );
}
