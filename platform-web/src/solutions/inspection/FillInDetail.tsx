"use client";

import Link from "next/link";
import { useCallback, useRef, useState } from "react";
import {
  ApprovalCard,
  ApprovalDialogs,
  ApprovalHeaderActions,
  useRecordApproval,
  type ApprovalState,
} from "@/components/records/ApprovalPanel";
import { RecordFiles } from "@/components/records/RecordFiles";
import { useRecord } from "@/components/records/RecordFormPage";
import { FillWorkspace } from "@/components/sheets/FillWorkspace";
import { DetailGrid, DetailItem, DetailPage, DetailSection } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { fillStatusTag } from "@/lib/sheets/fillTags";
import { footerText } from "@/lib/sheets/revision";
import type { FillSummary } from "@/lib/sheets/fillApi";
import { submitFillIn } from "./fillIns";
import { useRecordObjects } from "./objects";
import { useApplication } from "./application";
import { appBase } from "./appPaths";
import { text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

/**
 * One fill-in (Functional track FF1, ADR-0192; Submit and Cancel FA6A, ADR-0196): the facts of its header, the approval of
 * the record (the steps, and Approve and Reject for the people asked), and under them its cells in the grid and the form
 * view, against the version of the template it was made on.
 *
 * <p>The person who fills it in presses <strong>Submit</strong> or <strong>Cancel</strong>. Submit saves what was typed and
 * asks the API to send the fill-in: when no approval process applies it is stored, read-only, status Submitted; when one
 * does, the API changes nothing and the ordinary "Submit for approval" popup opens, so a process made in Setup changes the
 * behaviour with no code. Cancel leaves; what was saved stays a draft in the person's list.
 *
 * <p>It draws what the API answers and decides nothing. A person who is asked to review a fill-in but may not read its
 * header still reads the cells (the API allows exactly that one fill-in); the header facts and the approval are then not
 * drawn, and the decision is made in the inbox.
 */
export function FillInDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const application = useApplication();
  const objects = useRecordObjects();
  const beforeLeave = useRef<(next: () => void) => Promise<void>>(async (next) => next());
  const registerLeave = useCallback((leave: (next: () => void) => Promise<void>) => { beforeLeave.current = leave; }, []);
  const [refresh, setRefresh] = useState(0);
  const record = useRecord(objects.fill, id, refresh);
  const [approval, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const [dirty, setDirty] = useState(false);
  const [archived, setArchived] = useState(false);
  const onLoaded = useCallback((summary: FillSummary) => setArchived(Boolean(summary.archivedAt)), []);
  const decisions = useRecordApproval({
    objectApiName: objects.fill,
    recordId: id,
    refresh,
    onState: setApproval,
    onChanged: () => setRefresh((now) => now + 1),
  });

  const header = record.status === "ready" ? record.record : undefined;
  const fields = header?.fields ?? {};
  const name = text(fields.name) || "Fill-in";
  const status = text(fields.status);
  const tag = status ? fillStatusTag(status) : undefined;
  const templateId = text(fields.templateId);
  // The footer of the View page: the template's number and the revision the fill-in was made on (as the Sheet tab writes it).
  const templateRecord = useRecord(objects.template, templateId || undefined, refresh);
  const templateNumber = templateRecord.status === "ready" ? text(templateRecord.record.fields.number) : "";
  const footer = (versionNo: number) => footerText(templateNumber, versionNo);
  // Only a hint for the buttons: a draft or returned fill-in takes files from the person who started it, a sent or approved
  // one from a person who may correct it, and an archived one from nobody. The server decides on every call.
  const sendable = status === "DRAFT" || status === "RETURNED";
  const mayChangeFiles = !archived && (status === "DRAFT" || status === "RETURNED" || approval.mayOverride);

  // The cells have just been saved. The API says whether the fill-in was sent for good or a review is needed.
  const submit = async (): Promise<{ ok: boolean; message?: string }> => {
    const outcome = await submitFillIn(id);
    if (!outcome.ok) {
      return { ok: false, message: outcome.failure.message };
    }
    if (outcome.data.outcome === "REVIEW_NEEDED") {
      decisions.setActing("submit");
      return { ok: true, message: "A review is set up for fill-ins. Confirm it in the popup to send this fill-in." };
    }
    setRefresh((now) => now + 1);
    return { ok: true, message: "The fill-in was submitted. It is stored and can no longer be changed." };
  };

  if (record.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the fill-in…
      </p>
    );
  }

  return (
    <DetailPage
      listLabel="Record Control"
      onBack={() => void beforeLeave.current(onBack)}
      noBreadcrumb
      title={name}
      actions={header ? <ApprovalHeaderActions approval={decisions} submitButton={false} /> : undefined}
      notices={header ? <ApprovalDialogs approval={decisions} recordVersion={header.version} /> : undefined}
      testId="fill-in-detail"
    >
      {header ? (
        <DetailSection title="Details">
          <DetailGrid>
            <DetailItem label="Template">
              {templateId ? (
                <Link href={`${appBase(application)}/s-templates?open=${templateId}`}>{name}</Link>
              ) : (
                "Not available"
              )}
            </DetailItem>
            <DetailItem label="Status">
              {tag ? <Tag tone={TONES[tag.tone]}>{tag.text}</Tag> : "Not available"}
            </DetailItem>
            {templateId ? <TemplateNumber id={templateId} refresh={refresh} /> : null}
          </DetailGrid>
          {approval.locked ? (
            <p className="hint" role="note">
              This fill-in is waiting for a decision, so it is locked. The people who may correct a fill-in after it is sent
              can still correct it.
            </p>
          ) : null}
        </DetailSection>
      ) : null}

      {header ? <ApprovalCard approval={decisions} recordVersion={header.version} actions={false} /> : null}

      {header ? (
        <DetailSection title="Attachments" stack>
          <p className="hint">
            Photographs and documents that belong to this fill-in. Whoever can see the fill-in can open them; they are
            downloaded, never shown inside the page.
          </p>
          <RecordFiles objectApiName={objects.fill} recordId={id} mayChange={mayChangeFiles} />
        </DetailSection>
      ) : null}

      <DetailSection title="Fill-in" stack>
        {dirty ? (
          <p className="form-message" role="note" data-testid="unsaved-note">
            There are changes that are not saved. They save after a short pause; Submit and Cancel save them first.
          </p>
        ) : null}
        <FillWorkspace
          owner={{ object: objects.fill, id }}
          label={name}
          refreshToken={refresh}
          onDirty={setDirty}
          onLoaded={onLoaded}
          submit={header && sendable ? submit : undefined}
          onCancel={sendable ? onBack : undefined}
          registerLeave={registerLeave}
          footer={footer}
        />
      </DetailSection>
    </DetailPage>
  );
}

/** The ordinary record read supplies the stored number only while the caller may read it. */
function TemplateNumber({ id, refresh }: { id: string; refresh: number }) {
  const objects = useRecordObjects();
  const template = useRecord(objects.template, id, refresh);
  return <DetailItem label="Template number">{template.status === "ready" ? text(template.record.fields.number) || "—" : "—"}</DetailItem>;
}
