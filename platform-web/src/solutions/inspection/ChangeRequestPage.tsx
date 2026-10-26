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
import { SheetWorkspace } from "@/components/sheets/SheetWorkspace";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { formatInstant } from "@/lib/records/fieldValue";
import { downloadTemplateSheet } from "./api";
import {
  addChangeComment,
  becameWords,
  createRevisedCopy,
  placeOf,
  placeWords,
  statusWords,
  useChangeRequest,
  type ChangeRequestView,
} from "./changeRequests";
import { RegisterRequestPanel } from "./RegisterPanel";
import { saveBlob, useRegister } from "./register";
import { REQUEST_OBJECT, TEMPLATE_OBJECT } from "./template";

const LABELS = { approve: "Approve", reject: "Decline" } as const;

const templateHref = (templateId: string, tab?: string) =>
  `/apps/inspection/s-templates?open=${templateId}${tab ? `&tab=${tab}` : ""}`;

/**
 * One change request on its own page (Functional track FA6E, ADR-0199; the register and the revised sheet since FA8,
 * ADR-0202). It shows the register form in both views, a link to the revision in use now, the places the person pointed at,
 * the revised sheet and where the approval stands. The buttons of the approval are drawn by the approval; the page never
 * decides who may answer, make the revised copy or complete the register: it draws what the API says, and every act is
 * decided again by the server.
 */
export function ChangeRequestPage({
  id,
  onBack,
  onChanged,
}: {
  id: string;
  onBack: () => void;
  /** Called after an answer, a comment or a copy, so a page around this one can read its own state again. */
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
    <Body request={request} refresh={refresh} onChanged={changed} onBack={onBack} />
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
  return <Body request={request} refresh={refresh} onChanged={onChanged} onBack={onBack} approval={decisions} />;
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
  refresh,
  onChanged,
  onBack,
  approval,
}: {
  request: ChangeRequestView;
  refresh: number;
  onChanged(): void;
  onBack(): void;
  approval?: RecordApproval;
}) {
  const ids = useId();
  const [notice, setNotice] = useState<{ text: string; error: boolean } | undefined>();
  const [busy, setBusy] = useState(false);
  const [reply, setReply] = useState("");
  const register = useRegister(request.templateId, request.id, refresh);
  const tag = statusWords(request.status);
  const became = becameWords(request);
  const templateName = request.templateName ?? "Template";
  const title = request.templateNumber ? `${templateName} · ${request.templateNumber}` : templateName;
  const declined = request.status === "DECLINED" ? declinedBecause(approval) : undefined;
  const revNo = register.state.status === "ready" ? register.state.view.revNo : undefined;

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
  const makeCopy = () =>
    run(async () => {
      const outcome = await createRevisedCopy(request.templateId, request.id);
      return outcome.ok
        ? { ok: true, text: "The revised copy was made. Change it below, then send the request on." }
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
  const downloadCopy = async (versionNo: number) => {
    setNotice(undefined);
    const outcome = await downloadTemplateSheet(request.templateId, versionNo);
    if (!outcome.ok) {
      setNotice({ text: outcome.failure.message, error: true });
      return;
    }
    saveBlob(outcome.data, `${(request.templateNumber ?? "Template").replace(/[^A-Za-z0-9._-]/g, "-")}_revised-copy.xlsx`);
  };

  const actions = <>{approval ? <ApprovalHeaderActions approval={approval} submitButton={false} /> : null}</>;

  return (
    <DetailPage
      listLabel="Change requests"
      onBack={onBack}
      noBreadcrumb
      title={title}
      titleExtra={
        <>
          <Tag tone={tag.tone}>{tag.text}</Tag>
          {revNo ? (
            <span className="hint" data-testid="request-version">
              Revision {revNo} read
            </span>
          ) : null}
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
          <p>
            <Link href={templateHref(request.templateId, "sheet")} className="link-button" data-testid="current-revision-link">
              Current revision
            </Link>{" "}
            <span className="hint">opens the sheet of the template as it stands now, to read.</span>
          </p>
          {became ? <p data-testid="request-became">{became}.</p> : null}
          {declined ? (
            <p data-testid="request-declined-reason">
              <strong>Reason it was declined:</strong> <span className="comment-text">{declined}</span>
            </p>
          ) : null}

          <DetailSection title="Register" stack>
            {register.state.status === "loading" ? (
              <p role="status" className="hint">
                Opening the register…
              </p>
            ) : register.state.status === "failed" ? (
              <p role="alert" className="form-message">
                {register.state.failure.message}
              </p>
            ) : (
              <RegisterRequestPanel
                templateId={request.templateId}
                requestId={request.id}
                view={register.state.view}
                onChanged={register.set}
              />
            )}
          </DetailSection>

          <DetailSection title="Where on the sheet" stack>
            {request.comments.length === 0 ? (
              <p className="hint" data-testid="request-no-changes">
                The person who asked pointed at no place.
              </p>
            ) : (
              <ul className="request-place-list" aria-label="Places on the sheet">
                {request.comments.map((item) => {
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
          </DetailSection>

          <DetailSection title="Revised sheet" stack>
            {request.canCreateCopy ? (
              <div className="stack">
                <p className="hint">
                  Nothing is changed yet. Make the revised copy: a copy of the revision in use that you change as the request
                  asks. People who only read the template do not see it, and the revision in use stays as it is until the last
                  step approves.
                </p>
                <div>
                  <button type="button" className="button" disabled={busy} onClick={() => void makeCopy()} data-testid="create-copy">
                    Create revised copy
                  </button>
                </div>
              </div>
            ) : null}
            {request.resultingVersion !== undefined && request.resultingVersion !== null && request.canChangeCopy ? (
              <SheetWorkspace
                owner={{ object: TEMPLATE_OBJECT, id: request.templateId }}
                label={title}
                refreshToken={refresh}
                allowNewDraft={false}
                focusVersion={request.resultingVersion}
                actions={({ versionNo }) =>
                  versionNo === request.resultingVersion ? (
                    <button type="button" className="button button-secondary" onClick={() => void downloadCopy(versionNo)}>
                      Download as Excel
                    </button>
                  ) : null
                }
              />
            ) : null}
            {!request.canCreateCopy && !request.canChangeCopy ? (
              <p className="hint" data-testid="revised-sheet-note">
                {request.status === "DONE"
                  ? "The revised sheet was published. It is the revision in use."
                  : request.status === "DECLINED"
                    ? "The request ended without being published, so the revision in use is unchanged."
                    : "The revised sheet is made and changed by the people asked to decide this request."}
              </p>
            ) : null}
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
