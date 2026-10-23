"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { ApprovalPanel, type ApprovalState } from "@/components/records/ApprovalPanel";
import { RecordFiles } from "@/components/records/RecordFiles";
import { useRecord } from "@/components/records/RecordFormPage";
import { FillWorkspace } from "@/components/sheets/FillWorkspace";
import { DetailGrid, DetailItem, DetailPage, DetailSection } from "@/components/ui/lists/DetailPage";
import { Tag } from "@/components/ui/lists/Tag";
import { fillStatusTag } from "@/lib/sheets/fillTags";
import type { FillSummary } from "@/lib/sheets/fillApi";
import { FILL_OBJECT } from "./fillIns";
import { text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;

/**
 * One fill-in (Functional track FF1, ADR-0192): the facts of its header, the approval panel of the record (the ordinary
 * Submit for approval, Approve and Reject, which is how a fill-in is sent and reviewed), and under them its cells in the
 * grid and the form view, against the version of the template it was made on.
 *
 * <p>It draws what the API answers and decides nothing. The cells are saved on their own button; a fill-in is sent from the
 * approval panel with the cells as they are stored, so the page says so while there are changes that are not saved. A
 * person who is asked to review a fill-in but may not read its header still reads the cells (the API allows exactly that
 * one fill-in); the header facts and the approval panel are then not drawn, and the decision is made in the inbox.
 */
export function FillInDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const [refresh, setRefresh] = useState(0);
  const record = useRecord(FILL_OBJECT, id, refresh);
  const [approval, setApproval] = useState<ApprovalState>({ locked: false, mayOverride: false, mayApprove: false });
  const [dirty, setDirty] = useState(false);
  const [archived, setArchived] = useState(false);
  const onLoaded = useCallback((summary: FillSummary) => setArchived(Boolean(summary.archivedAt)), []);

  const header = record.status === "ready" ? record.record : undefined;
  const fields = header?.fields ?? {};
  const name = text(fields.name) || "Fill-in";
  const status = text(fields.status);
  const tag = status ? fillStatusTag(status) : undefined;
  const templateId = text(fields.templateId);
  // Only a hint for the buttons: a draft or returned fill-in takes files from the person who started it, a sent or approved
  // one from a person who may correct it, and an archived one from nobody. The server decides on every call.
  const mayChangeFiles = !archived && (status === "DRAFT" || status === "RETURNED" || approval.mayOverride);

  if (record.status === "loading") {
    return (
      <p role="status" className="hint">
        Opening the fill-in…
      </p>
    );
  }

  return (
    <DetailPage listLabel="Fill-ins" onBack={onBack} title={name} testId="fill-in-detail">
      {header ? (
        <DetailSection title="Details">
          <DetailGrid>
            <DetailItem label="Template">
              {templateId ? (
                <Link href={`/apps/inspection/s-templates?open=${templateId}`}>{name}</Link>
              ) : (
                "Not available"
              )}
            </DetailItem>
            <DetailItem label="Status">
              {tag ? <Tag tone={TONES[tag.tone]}>{tag.text}</Tag> : "Not available"}
            </DetailItem>
          </DetailGrid>
          {approval.locked ? (
            <p className="hint" role="note">
              This fill-in is waiting for a decision, so it is locked. The people who may correct a fill-in after it is sent
              can still correct it.
            </p>
          ) : null}
        </DetailSection>
      ) : null}

      {header ? (
        <ApprovalPanel
          objectApiName={FILL_OBJECT}
          recordId={id}
          recordVersion={header.version}
          refresh={refresh}
          onState={setApproval}
          onChanged={() => setRefresh((now) => now + 1)}
        />
      ) : null}

      {header ? (
        <DetailSection title="Attachments" stack>
          <p className="hint">
            Photographs and documents that belong to this fill-in. Whoever can see the fill-in can open them; they are
            downloaded, never shown inside the page.
          </p>
          <RecordFiles objectApiName={FILL_OBJECT} recordId={id} mayChange={mayChangeFiles} />
        </DetailSection>
      ) : null}

      <DetailSection title="Fill-in" stack>
        {dirty ? (
          <p className="form-message" role="note" data-testid="unsaved-note">
            There are changes that are not saved. Save them before you send the fill-in: only what is saved is sent.
          </p>
        ) : null}
        <FillWorkspace
          owner={{ object: FILL_OBJECT, id }}
          label={name}
          refreshToken={refresh}
          onDirty={setDirty}
          onLoaded={onLoaded}
        />
      </DetailSection>
    </DetailPage>
  );
}
