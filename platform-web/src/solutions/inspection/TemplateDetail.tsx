"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import {
  ApprovalCard,
  ApprovalDialogs,
  ApprovalHeaderActions,
  useRecordApproval,
  type ApprovalState,
} from "@/components/records/ApprovalPanel";
import { RecordDetails } from "@/components/records/RecordDetails";
import { RecordForm } from "@/components/records/RecordForm";
import { useRecord } from "@/components/records/RecordFormPage";
import { SheetWorkspace } from "@/components/sheets/SheetWorkspace";
import { Modal } from "@/components/ui/Modal";
import type { ActionMenuItem } from "@/components/ui/ActionMenu";
import { useObjectForm } from "@/lib/records/hooks";
import type { AnnotateContext, Place, SheetAnnotate } from "@/lib/sheets/annotate";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { retireTemplate, unpublishTemplate, useLifecycle, type TemplateLifecycleView } from "./api";
import { AnnotatePanel } from "./AnnotatePanel";
import { marksFromRequests, useChangeRequests } from "./changeRequests";
import { ChangeRequestsSection } from "./ChangeRequestsSection";
import { FillInsList } from "./FillInsPage";
import { lifecycleText, stateTag } from "./lifecycle";
import { PublishDialog } from "./PublishDialog";
import { TEMPLATE_OBJECT, text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;
const FORM_ID = "template-details-form";
const TABS = [
  { id: "details", label: "Details" },
  { id: "sheet", label: "Sheet" },
  { id: "fill-ins", label: "Fill-ins" },
] as const;
type TabId = (typeof TABS)[number]["id"];

const recordHref = (object: string, id: string) => `/records/${object}/${id}`;

/**
 * One template (Functional track FA3, FA4, UI3): a title with its status, the buttons that fit the member in the title
 * band (Edit on the Details tab, Submit for approval or Approve and Reject, Publish, and a "more" menu for the rare
 * ones), and three tabs: Details (the header fields, read first and edited in place, who it is published to, the steps of
 * the approval), Sheet (the versions, then one version with its grid and the change requests) and Fill-ins (the fill-ins
 * of this template, and the button that starts one). The open tab is in the address, so a reload or a link lands on it.
 *
 * <p>It draws what the API answers and decides nothing. The buttons follow the flags of the lifecycle the server sends
 * (`canPublish`, `canUnpublish`, `canRetire`) and the approval's own; every request that follows is decided again by the
 * server and a refusal is shown in its words. After an approval that the server says can be published, the Publish popup
 * opens by itself: approving and publishing are two acts of the server, done in one flow.
 */
export function TemplateDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const params = useSearchParams();
  const tab: TabId = TABS.find((entry) => entry.id === params?.get("tab"))?.id ?? "details";

  const [refresh, setRefresh] = useState(0);
  const record = useRecord(TEMPLATE_OBJECT, id, refresh);
  const { state: form } = useObjectForm(TEMPLATE_OBJECT, undefined, id);
  const { state: lifecycle, reload } = useLifecycle(id, refresh);
  const { state: requests, reload: reloadRequests } = useChangeRequests(id, refresh);
  // A place a comment in the list asked to be shown: the panel under the grid stands the grid on it and clears this.
  const [show, setShow] = useState<Place | null>(null);
  const [approval, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const [editing, setEditing] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [publishAfterApproval, setPublishAfterApproval] = useState(false);
  const [notice, setNotice] = useState<{ text: string; error: boolean } | undefined>();
  const [busy, setBusy] = useState(false);
  const [confirmRetire, setConfirmRetire] = useState(false);
  // The buttons of an open version (back, Form, Grid, Save) are drawn here, in the row of the tabs, on the Sheet tab only.
  const [toolbarSlot, setToolbarSlot] = useState<HTMLElement | null>(null);

  const changed = () => {
    setRefresh((now) => now + 1);
    reload();
    reloadRequests();
  };

  const decisions = useRecordApproval({
    objectApiName: TEMPLATE_OBJECT,
    recordId: id,
    refresh,
    onState: setApproval,
    onChanged: changed,
    onDecided: (kind) => {
      if (kind === "approve") {
        setPublishAfterApproval(true);
      }
    },
  });

  const view: TemplateLifecycleView | undefined = lifecycle.status === "ready" ? lifecycle.view : undefined;

  // After an approval, once the lifecycle has been read again: if the approval ended and the member may publish, ask where.
  useEffect(() => {
    if (!publishAfterApproval || lifecycle.status !== "ready") {
      return;
    }
    const mayPublish = lifecycle.view.canPublish;
    void Promise.resolve().then(() => {
      setPublishAfterApproval(false);
      if (mayPublish) {
        setPublishing(true);
      }
    });
  }, [publishAfterApproval, lifecycle]);

  const goTo = (next: TabId) => {
    router.replace(next === "details" ? `${pathname}?open=${id}` : `${pathname}?open=${id}&tab=${next}`);
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
  const tag = view ? stateTag(view.lifecycle.state) : undefined;
  const requestsView = requests.status === "ready" ? requests.view : undefined;
  const objectForm = form.status === "ready" ? form.form : undefined;
  const mayEdit = objectForm?.actions.update === true;
  const lockedForMe = approval.locked && !approval.mayOverride;

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

  const more: ActionMenuItem[] = [
    ...(view?.canUnpublish
      ? [
          {
            label: "Unpublish",
            onSelect: () => void act(() => unpublishTemplate(id), "The template was taken back from the people it was published to."),
          },
        ]
      : []),
    ...(view?.canRetire ? [{ label: "Retire", onSelect: () => setConfirmRetire(true), danger: true }] : []),
  ];

  const actions = editing ? (
    <>
      <button type="submit" form={FORM_ID} className="button" data-testid="template-save">
        Save
      </button>
      <button type="button" className="button button-secondary" onClick={() => setEditing(false)} data-testid="template-cancel">
        Cancel
      </button>
    </>
  ) : (
    <>
      {tab === "details" && mayEdit ? (
        <button
          type="button"
          className="button button-secondary"
          onClick={() => setEditing(true)}
          disabled={lockedForMe}
          title={lockedForMe ? "This template is locked while it is being approved." : undefined}
          data-testid="template-edit"
        >
          Edit
        </button>
      ) : null}
      <ApprovalHeaderActions approval={decisions} extra={more}>
        {view?.canPublish ? (
          <button type="button" className="button" onClick={() => setPublishing(true)} disabled={busy} data-testid="template-publish">
            {view.audience.length > 0 ? "Change publishing" : "Publish"}
          </button>
        ) : null}
      </ApprovalHeaderActions>
    </>
  );

  return (
    <DetailPage
      listLabel="Templates"
      onBack={onBack}
      noBreadcrumb
      title={name}
      titleExtra={
        tag && view ? (
          <>
            <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>
            <span className="hint" data-testid="lifecycle-text">
              {lifecycleText(view.lifecycle)}
            </span>
          </>
        ) : undefined
      }
      actions={actions}
      notices={
        <>
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
          <ApprovalDialogs approval={decisions} recordVersion={record.record.version} />
        </>
      }
      testId="template-detail"
    >
      <div className="detail-tabs-row">
        <div role="tablist" aria-label="Parts of the template" className="detail-tabs">
          {TABS.map((entry) => (
            <button
              key={entry.id}
              type="button"
              role="tab"
              id={`template-tab-${entry.id}`}
              aria-selected={tab === entry.id}
              aria-controls={`template-panel-${entry.id}`}
              className="detail-tab"
              disabled={editing && tab !== entry.id}
              title={editing && tab !== entry.id ? "Save or cancel your changes first." : undefined}
              onClick={() => goTo(entry.id)}
            >
              {entry.label}
            </button>
          ))}
        </div>
        <div className="detail-tabs-tools" ref={setToolbarSlot} />
      </div>

      <div role="tabpanel" id={`template-panel-${tab}`} aria-labelledby={`template-tab-${tab}`} className="stack">
        {tab === "details" ? (
          <>
            {editing && objectForm ? (
              <RecordForm
                formId={FORM_ID}
                hideActions
                objectApiName={TEMPLATE_OBJECT}
                form={objectForm}
                record={record.record}
                recordHref={recordHref}
                onRecordTypeChange={() => undefined}
                onSaved={() => {
                  setEditing(false);
                  changed();
                }}
                onCancel={() => setEditing(false)}
              />
            ) : objectForm ? (
              <RecordDetails objectApiName={TEMPLATE_OBJECT} form={objectForm} record={record.record} recordHref={recordHref} />
            ) : (
              <DetailGrid>
                <DetailItem label="Category">{text(fields.category) || "None"}</DetailItem>
                <DetailItem label="Sub-category">{text(fields.subCategory) || "None"}</DetailItem>
              </DetailGrid>
            )}
            {!editing ? (
              <>
                {approval.mayApprove && view?.numberPreview ? (
                  <DetailGrid>
                    <DetailItem label="Number when approved" tone="mono">
                      <span data-testid="number-note">
                        {view.numberPreviewComplete ? view.numberPreview : `${view.numberPreview}…`}
                      </span>
                    </DetailItem>
                  </DetailGrid>
                ) : null}
                <DetailSection title="Published to" stack>
                  {view && view.audience.length > 0 ? (
                    <ul aria-label="Published to" className="stack" data-testid="audience">
                      {view.audience.map((entry) => (
                        <li key={`${entry.location}/${entry.department}`}>
                          {entry.locationLabel} · {entry.departmentLabel}{" "}
                          <span className="hint">
                            version {entry.versionNo}
                            {entry.groupAvailable ? "" : ", its group no longer exists"}
                          </span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="hint" data-testid="audience-none">
                      Not published to any department.
                    </p>
                  )}
                </DetailSection>
                <ApprovalCard approval={decisions} recordVersion={record.record.version} actions={false} />
              </>
            ) : null}
          </>
        ) : null}

        {tab === "sheet" ? (
          <>
            <SheetWorkspace
              owner={{ object: TEMPLATE_OBJECT, id }}
              label={name}
              correctInReview={approval.mayOverride}
              refreshToken={refresh}
              annotate={annotate}
              listFirst
              toolbarSlot={toolbarSlot}
            />
            <DetailSection title="Change requests" stack>
              <ChangeRequestsSection
                templateId={id}
                state={requests}
                onChanged={changed}
                onShow={annotate ? setShow : undefined}
              />
            </DetailSection>
          </>
        ) : null}

        {tab === "fill-ins" ? <FillInsList templateId={id} embedded /> : null}
      </div>

      {publishing && view ? (
        <Modal title="Publish this template" onClose={() => setPublishing(false)}>
          <PublishDialog
            templateId={id}
            lifecycle={view}
            inModal
            onClose={() => setPublishing(false)}
            onDone={() => {
              setPublishing(false);
              setNotice({ text: "The template is published to the people of the departments you chose.", error: false });
              changed();
            }}
          />
        </Modal>
      ) : null}
      {confirmRetire ? (
        <Modal title="Retire this template" onClose={() => setConfirmRetire(false)}>
          <div className="stack" role="alertdialog" aria-label="Retire this template">
            <p>
              It is kept for good and stays readable to the people who had it, but it can no longer be used, and its number
              is never given to another template.
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
        </Modal>
      ) : null}
    </DetailPage>
  );
}
