"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useRef, useState } from "react";
import {
  ApprovalCard,
  ApprovalDialogs,
  ApprovalHeaderActions,
  useRecordApproval,
  type ApprovalState,
} from "@/components/records/ApprovalPanel";
import { ApprovalHistoryList, type ApprovalHistoryEntry } from "@/components/records/ApprovalHistoryList";
import { useApprovalHistory } from "@/lib/approvals/history";
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
import { ActionPlanSection } from "./ActionPlanSection";
import { text } from "./template";

const TONES = { active: "ok", draft: "on", review: "on", done: "quiet", stopped: "error" } as const;
const TABS = [
  { id: "details", label: "Details" },
  { id: "sheet", label: "Sheet" },
] as const;
type TabId = (typeof TABS)[number]["id"];

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
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const params = useSearchParams();
  // The open tab is in the address (as the template's), so a link or a notice can open the sheet; no tab means Details.
  const tab: TabId = TABS.find((entry) => entry.id === params?.get("tab"))?.id ?? "details";
  // The sheet is drawn the first time its tab is opened and then kept (hidden on the other tab), so cells typed and not
  // yet saved, and the autosave queue, are never lost by looking at the details.
  const [sheetOpened, setSheetOpened] = useState(tab === "sheet");
  if (tab === "sheet" && !sheetOpened) {
    setSheetOpened(true);
  }
  const goTo = (next: TabId) => {
    const query = new URLSearchParams(params?.toString() ?? "");
    if (next === "details") {
      query.delete("tab");
    } else {
      query.set("tab", next);
    }
    const search = query.toString();
    router.replace(search ? `${pathname}?${search}` : pathname);
  };
  const [archived, setArchived] = useState(false);
  const onLoaded = useCallback((summary: FillSummary) => setArchived(Boolean(summary.archivedAt)), []);
  const decisions = useRecordApproval({
    objectApiName: objects.fill,
    recordId: id,
    refresh,
    onState: setApproval,
    onChanged: () => setRefresh((now) => now + 1),
  });

  // The approvals of this fill-in as a history by day, the same list the template page draws (ADR-0205). A person who may not
  // read the history, or a fill-in with none, gets nothing here; the card and its buttons still come from the record's panel.
  const history = useApprovalHistory(objects.fill, [id], refresh)[id] ?? [];
  const flowEntries: ApprovalHistoryEntry[] = history.map((instance) => ({
    key: instance.summary.id,
    at: instance.summary.startedAt,
    title: "Approval",
    instance,
  }));

  const header = record.status === "ready" ? record.record : undefined;
  const fields = header?.fields ?? {};
  const name = text(fields.name) || "Fill-in";
  const status = text(fields.status);
  const tag = status ? fillStatusTag(status) : undefined;
  // A decimal may come as digits in a text or as a number; it is shown as it was stored, never calculated here.
  const scoreText = typeof fields.score === "number" || typeof fields.score === "string" ? String(fields.score) : "";
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
        <div className="detail-tabs-row">
          <div role="tablist" aria-label="Parts of the fill-in" className="detail-tabs">
            {TABS.map((entry) => (
              <button
                key={entry.id}
                type="button"
                role="tab"
                id={`fill-in-tab-${entry.id}`}
                aria-selected={tab === entry.id}
                aria-controls={`fill-in-panel-${entry.id}`}
                className="detail-tab"
                onClick={() => goTo(entry.id)}
              >
                {entry.label}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {header ? (
        <div
          role="tabpanel"
          id="fill-in-panel-details"
          aria-labelledby="fill-in-tab-details"
          className="stack"
          hidden={tab !== "details"}
        >
          <div className={flowEntries.length > 0 ? "detail-with-flow detail-has-flow" : "detail-with-flow"}>
            <div className="stack">
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
                  {scoreText ? <DetailItem label="Score">{scoreText}</DetailItem> : null}
                  {text(fields.result) ? (
                    <DetailItem label="Result">
                      {text(fields.result) === "ACTION_PLAN" ? <Tag tone="error">Below the limit</Tag> : <Tag tone="ok">Passed</Tag>}
                    </DetailItem>
                  ) : null}
                </DetailGrid>
                {approval.locked ? (
                  <p className="hint" role="note">
                    This fill-in is waiting for a decision, so it is locked. The people who may correct a fill-in after it is sent
                    can still correct it.
                  </p>
                ) : null}
              </DetailSection>

              <ApprovalCard approval={decisions} recordVersion={header.version} actions={false} steps={flowEntries.length === 0} />

              <ActionPlanSection fillId={id} refresh={refresh} />

              <DetailSection title="Attachments" stack>
                <p className="hint">
                  Photographs and documents that belong to this fill-in. Whoever can see the fill-in can open them; they are
                  downloaded, never shown inside the page.
                </p>
                <RecordFiles objectApiName={objects.fill} recordId={id} mayChange={mayChangeFiles} />
              </DetailSection>
            </div>
            {flowEntries.length > 0 ? (
              <aside className="detail-flow-column" aria-label="Approvals of this fill-in" data-testid="fill-in-approvals">
                <ApprovalHistoryList entries={flowEntries} label="Approvals of this fill-in" />
              </aside>
            ) : null}
          </div>
        </div>
      ) : null}

      {sheetOpened || !header ? (
        <div
          role={header ? "tabpanel" : undefined}
          id="fill-in-panel-sheet"
          aria-labelledby={header ? "fill-in-tab-sheet" : undefined}
          className="stack"
          hidden={header ? tab !== "sheet" : false}
        >
          <FillWorkspace
            owner={{ object: objects.fill, id }}
            label={name}
            refreshToken={refresh}
            onLoaded={onLoaded}
            submit={header && sendable ? submit : undefined}
            onCancel={sendable ? onBack : undefined}
            registerLeave={registerLeave}
            footer={footer}
          />
        </div>
      ) : null}
    </DetailPage>
  );
}

/** The ordinary record read supplies the stored number only while the caller may read it. */
function TemplateNumber({ id, refresh }: { id: string; refresh: number }) {
  const objects = useRecordObjects();
  const template = useRecord(objects.template, id, refresh);
  return <DetailItem label="Template number">{template.status === "ready" ? text(template.record.fields.number) || "—" : "—"}</DetailItem>;
}
