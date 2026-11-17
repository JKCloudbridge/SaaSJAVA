"use client";

import { ActionNotice } from "@/components/ui/ActionNotice";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import {
  ApprovalCard,
  ApprovalDialogs,
  ApprovalHeaderActions,
  useRecordApproval,
  type ApprovalState,
} from "@/components/records/ApprovalPanel";
import { RecordDetails } from "@/components/records/RecordDetails";
import { RecordForm, type FormSlotAccess } from "@/components/records/RecordForm";
import { useRecord } from "@/components/records/RecordFormPage";
import { SheetWorkspace } from "@/components/sheets/SheetWorkspace";
import { Modal } from "@/components/ui/Modal";
import type { ActionMenuItem } from "@/components/ui/ActionMenu";
import { useObjectForm } from "@/lib/records/hooks";
import { useAbilities } from "@/lib/session/SessionProvider";
import { canSetUp } from "@/lib/shell/areas";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import {
  downloadTemplateSheet,
  retireTemplate,
  unpublishTemplate,
  useLifecycle,
  type TemplateLifecycleView,
} from "./api";
import { AskForChange } from "./AskForChange";
import { useChangeRequests } from "./changeRequests";
import { ChangeRequestsList } from "./ChangeRequestsPage";
import { FillInsList } from "./FillInsPage";
import { lifecycleText, stateTag } from "./lifecycle";
import { OpenRequestsMark } from "./OpenRequestsMark";
import { PlantPublishDialog } from "./PlantPublishDialog";
import { usePlantState } from "./plantPublication";
import { PublishDialog } from "./PublishDialog";
import { SheetOptionsDialog } from "./SheetOptionsDialog";
import { TemplateApprovals } from "./TemplateApprovals";
import { text } from "./template";
import { useRecordObjects } from "./objects";
import { useEntryLabel } from "./application";
import { PartField, NumberField } from "./TemplateNumberFields";
import { detailDisplay, templateSections } from "./templateDetails";
import { hiddenIdentityFields, identityOf, sameIdentity, useNumberingInfo, type IdentityValues } from "./numbering";
import { downloadNameFor, footerTextWithDay, revisionNumber, revisionWords } from "@/lib/sheets/revision";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;
const FORM_ID = "template-details-form";
const TABS = [
  { id: "details", label: "Details" },
  { id: "sheet", label: "Sheet" },
  { id: "fill-ins", label: "Fill-ins" },
  { id: "change-requests", label: "Change requests" },
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
  const objects = useRecordObjects();
  const listLabel = useEntryLabel("templates", "Document Control");
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const params = useSearchParams();
  const tab: TabId = TABS.find((entry) => entry.id === params?.get("tab"))?.id ?? "details";

  const [refresh, setRefresh] = useState(0);
  const record = useRecord(objects.template, id, refresh);
  const { state: form } = useObjectForm(objects.template, undefined, id);
  const { state: lifecycle, reload } = useLifecycle(id, refresh);
  const { state: requests, reload: reloadRequests } = useChangeRequests(id, refresh);
  const plantState = usePlantState(id, refresh);
  const plants = plantState.status === "ready" ? plantState.state : undefined;
  const [plantsOpen, setPlantsOpen] = useState(false);
  const [approval, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const [editing, setEditing] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [optionsOpen, setOptionsOpen] = useState(false);
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
    objectApiName: objects.template,
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

  // A note for the people who can set things up: without an approval process nobody sees a Submit button, and nothing
  // else says why. It is a courtesy; the server decides what anyone may do.
  const abilities = useAbilities();
  const mayAdminister = abilities !== null && canSetUp(abilities);

  const view: TemplateLifecycleView | undefined = lifecycle.status === "ready" ? lifecycle.view : undefined;

  // The number builds itself on the screen from what is typed (FA11B). The server says which parts the identification asks
  // for and where its counter stands; reading uses the identity the template holds, editing the one being typed.
  const [typed, setTyped] = useState<IdentityValues | undefined>();
  const heldIdentity = identityOf(record.status === "ready" ? record.record.fields : {});
  const identity = editing && typed !== undefined ? typed : heldIdentity;
  const numberingInfo = useNumberingInfo(record.status === "ready" ? identity : undefined);
  const hiddenFields = hiddenIdentityFields(numberingInfo, identity, true);
  const onValues = useCallback((values: Readonly<Record<string, string>>) => {
    const next = identityOf(values);
    setTyped((current) => (current !== undefined && sameIdentity(current, next) ? current : next));
  }, []);

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
        listLabel={listLabel}
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
  // The approvals sit beside the details, one entry for each by date, once the template has had a request (FA6E).
  const flowShown = decisions.panel?.available === true && decisions.panel.current !== undefined;

  // The version in use can be asked about by anyone the API says may ask for a change (FA5; no place on the sheet since FA10).
  const mayAsk = view !== undefined && view.lifecycle.activeVersion > 0 && (requestsView?.canRequest || requestsView?.inProgress);
  // A revised copy opened from a change request (FA10): the address names the stored version to open.
  const openVersion = Number(params?.get("version"));
  const focusVersion = Number.isInteger(openVersion) && openVersion > 0 ? openVersion : undefined;
  // The people who change the revised copy of a request may download it whatever the template's option says (the server
  // decides again).
  const heldCopyOf = (versionNo: number) =>
    requestsView?.requests.some((request) => request.resultingVersion === versionNo && request.canChangeCopy) === true;

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

  // The footer row of the sheet (FA7): the number and the revision in use; the revision shown when none is in use yet.
  // The revision in use is the platform's saved one (gapless, FA8); before any is in use, the revision of the sheet shown.
  const footerRevision = (shown: number) =>
    view && view.lifecycle.activeVersion > 0 && view.revision ? view.revision : revisionNumber(shown);
  // The day of the publish (MC1, ADR-0257) is written only for the revision in use, and only where the application's
  // footer carries it; the server decides which (the flag), the browser only draws.
  const footerFor = (shown: number) =>
    footerTextWithDay(
      view?.number,
      footerRevision(shown),
      view?.footerDate && view.lifecycle.activeVersion > 0 && shown === view.lifecycle.activeVersion
        ? view.lastRevisedAt
        : undefined,
    );

  // Download of the revision that is open: the server decides again whether it is allowed and whether it may be read.
  const download = async (shown: number) => {
    setNotice(undefined);
    const outcome = await downloadTemplateSheet(id, shown);
    if (!outcome.ok) {
      setNotice({ text: outcome.failure.message, error: true });
      return;
    }
    const url = URL.createObjectURL(outcome.data);
    const link = document.createElement("a");
    link.href = url;
    link.download = downloadNameFor(view?.number, footerRevision(shown));
    link.click();
    URL.revokeObjectURL(url);
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
      <ApprovalHeaderActions
        approval={decisions}
        extra={more}
        submitButton={view?.canSubmit !== false}
      >
        {view?.canSetOptions ? (
          <button
            type="button"
            className="button button-secondary"
            onClick={() => setOptionsOpen(true)}
            disabled={busy}
            data-testid="template-sheet-options"
          >
            Sheet options
          </button>
        ) : null}
        {view?.canPublish ? (
          <button type="button" className="button" onClick={() => setPublishing(true)} disabled={busy} data-testid="template-publish">
            {view.audience.length > 0 ? "Change publishing" : "Publish"}
          </button>
        ) : null}
        {plants?.canPublish ? (
          <button
            type="button"
            className="button button-secondary"
            onClick={() => setPlantsOpen(true)}
            disabled={busy}
            data-testid="template-publish-plants"
          >
            {plants.plants.length > 0 ? "Change plants" : "Publish to plants"}
          </button>
        ) : null}
      </ApprovalHeaderActions>
    </>
  );

  return (
    <DetailPage
      listLabel={listLabel}
      onBack={onBack}
      noBreadcrumb
      title={name}
      titleExtra={
        tag && view ? (
          <>
            <Tag tone={TONES[tag.tone]}>{tag.text}</Tag>
            {view.viewOnly ? <Tag tone="quiet">View only</Tag> : null}
            <span className="hint" data-testid="lifecycle-text">
              {lifecycleText(view.lifecycle, view.revision)}
            </span>
          </>
        ) : undefined
      }
      actions={actions}
      notices={
        <>
          {notice ? (
            <ActionNotice key={notice.text} text={notice.text} error={notice.error} className="hint" occurrence={notice} />
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
              {entry.id === "change-requests" ? <OpenRequestsMark count={view?.openChangeRequests ?? 0} /> : null}
            </button>
          ))}
        </div>
        <div className="detail-tabs-tools" ref={setToolbarSlot} />
      </div>

      <div role="tabpanel" id={`template-panel-${tab}`} aria-labelledby={`template-tab-${tab}`} className="stack">
        {tab === "details" ? (
          <div className={flowShown ? "detail-with-flow detail-has-flow" : "detail-with-flow"}>
            <div className="stack">
              {editing && objectForm ? (
                <RecordForm
                  formId={FORM_ID}
                  hideActions
                  objectApiName={objects.template}
                  form={templateSections(objectForm, view, true)}
                  display={detailDisplay(view, record.record)}
                  hide={hiddenFields}
                  onValues={onValues}
                  slots={{
                    number: (access: FormSlotAccess) => (
                      <NumberField
                        access={access}
                        info={numberingInfo}
                        savedNumber={
                          text(fields.number) !== "" && sameIdentity(identity, heldIdentity) ? text(fields.number) : undefined
                        }
                      />
                    ),
                    partA: (access: FormSlotAccess) => (
                      <PartField name="partA" label="Part A" access={access} />
                    ),
                    partB: (access: FormSlotAccess) => (
                      <PartField name="partB" label="Part B" access={access} />
                    ),
                  }}
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
                <RecordDetails
                  objectApiName={objects.template}
                  form={templateSections(objectForm, view, false)}
                  hide={hiddenFields}
                  record={record.record}
                  recordHref={recordHref}
                  display={detailDisplay(view, record.record)}
                />
              ) : (
                <DetailGrid>
                  <DetailItem label="Level">{text(fields.level) || "None"}</DetailItem>
                  <DetailItem label="Document type">{text(fields.documentType) || "None"}</DetailItem>
                  <DetailItem label="Identification">{text(fields.category) || "None"}</DetailItem>
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
                              {revisionWords(entry.versionNo).toLowerCase()}
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
                  <ApprovalCard approval={decisions} recordVersion={record.record.version} actions={false} steps={false} />
                  {decisions.panel && !decisions.panel.available && mayAdminister ? (
                    <p className="hint" data-testid="no-approval-note">
                      No approval process is set up for templates yet.
                    </p>
                  ) : null}
                </>
              ) : null}
            </div>
            {flowShown ? (
              <TemplateApprovals templateId={id} lifecycle={view} requests={requestsView} refresh={refresh} />
            ) : null}
          </div>
        ) : null}

        {tab === "sheet" ? (
          <>
            <SheetWorkspace
              owner={{ object: objects.template, id }}
              label={name}
              correctInReview={approval.mayOverride}
              refreshToken={refresh}
              key={focusVersion ?? "list"}
              listFirst
              focusVersion={focusVersion}
              onVersionsChanged={reload}
              allowNewDraft={!view || (view.lifecycle.activeVersion === 0 && view.lifecycle.retiredVersion === 0)}
              toolbarSlot={toolbarSlot}
              footer={view ? footerFor : undefined}
              actions={(open) =>
                view?.canDownload || heldCopyOf(open.versionNo) ? (
                  <button
                    type="button"
                    className="button button-secondary"
                    onClick={() => void download(open.versionNo)}
                    data-testid="sheet-download"
                  >
                    Download
                  </button>
                ) : null
              }
            />
            {mayAsk && view ? (
              <AskForChange
                templateId={id}
                versionNo={view.lifecycle.activeVersion}
                view={requestsView}
                onSent={changed}
                toolbarSlot={toolbarSlot}
              />
            ) : null}
          </>
        ) : null}

        {tab === "fill-ins" ? <FillInsList templateId={id} embedded /> : null}

        {tab === "change-requests" ? (
          <ChangeRequestsList
            templateId={id}
            openId={params?.get("request")}
            onOpen={(request) => router.replace(`${pathname}?open=${id}&tab=change-requests&request=${request}`)}
            onClose={() => router.replace(`${pathname}?open=${id}&tab=change-requests`)}
            onChanged={changed}
          />
        ) : null}
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
      {plantsOpen && plants ? (
        <Modal title="Publish to plants" onClose={() => setPlantsOpen(false)}>
          <PlantPublishDialog
            templateId={id}
            state={plants}
            inModal
            onClose={() => setPlantsOpen(false)}
            onDone={() => {
              setPlantsOpen(false);
              setNotice({ text: "The plants and their reviewers are saved.", error: false });
              changed();
            }}
          />
        </Modal>
      ) : null}
      {optionsOpen && view ? (
        <Modal title="Sheet options" onClose={() => setOptionsOpen(false)}>
          <SheetOptionsDialog
            templateId={id}
            lifecycle={view}
            onClose={() => setOptionsOpen(false)}
            onDone={() => {
              setOptionsOpen(false);
              setNotice({ text: "The sheet options are saved.", error: false });
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
