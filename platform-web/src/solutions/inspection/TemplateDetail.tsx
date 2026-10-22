"use client";

import Link from "next/link";
import { useState } from "react";
import { ApprovalPanel, type ApprovalState } from "@/components/records/ApprovalPanel";
import { useRecord } from "@/components/records/RecordFormPage";
import { SheetWorkspace } from "@/components/sheets/SheetWorkspace";
import type { AnnotateContext, Place, SheetAnnotate } from "@/lib/sheets/annotate";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { retireTemplate, unpublishTemplate, useLifecycle, type TemplateLifecycleView } from "./api";
import { AnnotatePanel } from "./AnnotatePanel";
import { marksFromRequests, useChangeRequests } from "./changeRequests";
import { ChangeRequestsSection } from "./ChangeRequestsSection";
import { lifecycleText, stateTag } from "./lifecycle";
import { PublishDialog } from "./PublishDialog";
import { TEMPLATE_OBJECT, text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

/**
 * One template (Functional track FA3, FA4): the facts of its header with its lifecycle ("Active v3, v4 in review"), the
 * approval panel of the record (the ordinary Submit for approval, Approve and Reject), who it is published to with Publish,
 * Unpublish and Retire, and under them its sheet with the history of its versions.
 *
 * <p>It draws what the API answers and decides nothing. The buttons follow the flags of the lifecycle the server sends
 * (`canPublish`, `canUnpublish`, `canRetire`) and the approval panel's own; every request that follows is decided again by
 * the server and a refusal is shown in its words. The line that says an approval gives the template its number is drawn
 * only while the approval is the member's to give, and shows the start of the number, never the counter.
 */
export function TemplateDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const [refresh, setRefresh] = useState(0);
  const record = useRecord(TEMPLATE_OBJECT, id, refresh);
  const { state: lifecycle, reload } = useLifecycle(id, refresh);
  const { state: requests, reload: reloadRequests } = useChangeRequests(id, refresh);
  // A place a comment in the list asked to be shown: the panel under the grid stands the grid on it and clears this.
  const [show, setShow] = useState<Place | null>(null);
  const [approval, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const [publishing, setPublishing] = useState(false);
  const [notice, setNotice] = useState<{ text: string; error: boolean } | undefined>();
  const [busy, setBusy] = useState(false);
  const [confirmRetire, setConfirmRetire] = useState(false);

  const changed = () => {
    setRefresh((now) => now + 1);
    reload();
    reloadRequests();
  };

  if (record.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the template…
      </p>
    );
  }
  if (record.status === "failed") {
    return (
      <MissingDetail
        listLabel="Templates"
        onBack={onBack}
        what="This template is not available. It may have been removed, or it may not be part of your access."
      />
    );
  }
  const fields = record.record.fields;
  const name = text(fields.name) || "Untitled template";
  const view: TemplateLifecycleView | undefined = lifecycle.status === "ready" ? lifecycle.view : undefined;
  const tag = view ? stateTag(view.lifecycle.state) : undefined;
  const requestsView = requests.status === "ready" ? requests.view : undefined;

  // Annotate mode (FA5): the version in use can be pointed at by anyone the API says may ask for a change.
  const annotate: SheetAnnotate | undefined =
    view && view.lifecycle.activeVersion > 0 && requestsView?.canRequest
      ? {
          versionNo: view.lifecycle.activeVersion,
          marks: marksFromRequests(requestsView),
          panel: (context: AnnotateContext) => (
            <AnnotatePanel
              templateId={id}
              context={context}
              view={requestsView}
              show={show}
              onShown={() => setShow(null)}
              onSent={changed}
            />
          ),
        }
      : undefined;

  const act = async (run: () => ReturnType<typeof unpublishTemplate>, done: string) => {
    setBusy(true);
    setNotice(undefined);
    const outcome = await run();
    setBusy(false);
    setConfirmRetire(false);
    if (outcome.ok) {
      setNotice({ text: done, error: false });
      changed();
      return;
    }
    setNotice({ text: outcome.failure.message, error: true });
  };

  return (
    <DetailPage
      listLabel="Templates"
      onBack={onBack}
      title={name}
      actions={
        <Link className="button button-secondary" href={`/records/${TEMPLATE_OBJECT}/${id}`}>
          Edit the details
        </Link>
      }
      testId="template-detail"
    >
      <DetailSection title="Details">
        <DetailGrid>
          <DetailItem label="Category">{text(fields.category) || "None"}</DetailItem>
          <DetailItem label="Sub-category">{text(fields.subCategory) || "None"}</DetailItem>
          <DetailItem label="Number" tone="mono">
            {text(fields.number) || "Not numbered yet"}
          </DetailItem>
          <DetailItem label="Status">
            {tag && view ? (
              <>
                <Tag tone={TONES[tag.tone]}>{tag.text}</Tag> <span data-testid="lifecycle-text">{lifecycleText(view.lifecycle)}</span>
              </>
            ) : lifecycle.status === "failed" ? (
              "Not available"
            ) : (
              "Loading…"
            )}
          </DetailItem>
        </DetailGrid>
        {approval.locked ? (
          <p className="hint" role="note">
            A version of this template is waiting for approval, so its details are locked. The people who approve it can
            still correct it.
          </p>
        ) : null}
        {view && view.lifecycle.inReviewVersion > 0 && view.lifecycle.draftVersion > 0 ? (
          <p className="hint" role="note" data-testid="draft-waits">
            Version {view.lifecycle.draftVersion} is a draft. It can be sent for approval after the approval of version{" "}
            {view.lifecycle.inReviewVersion} has ended: only one version of a template is approved at a time.
          </p>
        ) : null}
        {approval.mayApprove && view?.numberPreview ? (
          <p className="hint" role="note" data-testid="number-note">
            {view.numberPreviewComplete
              ? `Approving this gives it its number, ${view.numberPreview}.`
              : `Approving this gives it its number, ${view.numberPreview}…, followed by the next serial.`}
          </p>
        ) : null}
      </DetailSection>

      <ApprovalPanel
        objectApiName={TEMPLATE_OBJECT}
        recordId={id}
        recordVersion={record.record.version}
        refresh={refresh}
        onState={setApproval}
        onChanged={changed}
      />

      <DetailSection title="Publishing" stack>
        {notice ? (
          <p className={notice.error ? "form-message" : "hint"} role={notice.error ? "alert" : "status"}>
            {notice.text}
          </p>
        ) : null}
        {lifecycle.status === "failed" ? (
          <p className="form-message" role="alert">
            {lifecycle.failure.message}
          </p>
        ) : null}
        {view ? (
          <>
            {view.audience.length === 0 ? (
              <p className="hint" data-testid="audience-none">
                {view.lifecycle.activeVersion > 0
                  ? "The version in use is not published to any department."
                  : "It is not published to anyone. People who do not approve or write it cannot see it."}
              </p>
            ) : (
              <ul aria-label="Published to" className="stack" data-testid="audience">
                {view.audience.map((entry) => (
                  <li key={`${entry.location}/${entry.department}`}>
                    {entry.locationLabel} · {entry.departmentLabel}{" "}
                    <span className="hint">
                      sees version {entry.versionNo}
                      {entry.groupAvailable ? "" : ", but its group no longer exists, so nobody reaches it this way"}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            <div className="split">
              {view.canPublish ? (
                <button type="button" className="button" onClick={() => setPublishing(true)} disabled={busy}>
                  {view.audience.length > 0 ? "Change who it is published to" : "Publish"}
                </button>
              ) : null}
              {view.canUnpublish ? (
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={() => void act(() => unpublishTemplate(id), "The template was taken back from the people it was published to.")}
                  disabled={busy}
                >
                  Unpublish
                </button>
              ) : null}
              {view.canRetire ? (
                <button type="button" className="button button-danger" onClick={() => setConfirmRetire(true)} disabled={busy}>
                  Retire
                </button>
              ) : null}
            </div>
            {confirmRetire ? (
              <div role="alertdialog" aria-label="Retire this template" className="card stack">
                <p>
                  Retire this template? It is kept for good and stays readable to the people who had it, but it can no
                  longer be used, and its number is never given to another template.
                </p>
                <div className="split">
                  <button
                    type="button"
                    className="button button-danger"
                    onClick={() => void act(() => retireTemplate(id), "The template is retired.")}
                    disabled={busy}
                  >
                    Retire it
                  </button>
                  <button type="button" className="button button-secondary" onClick={() => setConfirmRetire(false)}>
                    Keep it
                  </button>
                </div>
              </div>
            ) : null}
            {publishing ? (
              <PublishDialog
                templateId={id}
                lifecycle={view}
                onClose={() => setPublishing(false)}
                onDone={() => {
                  setPublishing(false);
                  setNotice({ text: "The template is published to the people of the departments you chose.", error: false });
                  changed();
                }}
              />
            ) : null}
          </>
        ) : null}
      </DetailSection>

      <DetailSection title="Sheet" stack>
        <SheetWorkspace
          owner={{ object: TEMPLATE_OBJECT, id }}
          label={name}
          correctInReview={approval.mayOverride}
          refreshToken={refresh}
          annotate={annotate}
        />
      </DetailSection>

      <DetailSection title="Change requests" stack>
        <ChangeRequestsSection
          templateId={id}
          state={requests}
          onChanged={changed}
          onShow={annotate ? setShow : undefined}
        />
      </DetailSection>
    </DetailPage>
  );
}
