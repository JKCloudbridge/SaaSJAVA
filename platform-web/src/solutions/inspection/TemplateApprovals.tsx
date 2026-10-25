"use client";

import { ApprovalHistoryList, type ApprovalHistoryEntry } from "@/components/records/ApprovalHistoryList";
import { useApprovalHistory } from "@/lib/approvals/history";
import { becameWords, type ChangeRequestsView } from "./changeRequests";
import type { TemplateLifecycleView } from "./api";
import { REQUEST_OBJECT, TEMPLATE_OBJECT } from "./template";

/** The most change requests whose approval is drawn; the newest are the ones people look for. */
const MOST_REQUESTS = 20;

/**
 * The approvals of a template as a history by date (Functional track FA6E, ADR-0199): one entry for the template's first
 * approval ("New template"), one for each later version ("New version 3"), and one for each change request that went through
 * an approval ("Change requested", with the version it became), newest first, the newest open. Each opens to the same flow
 * the approval card draws. It is made from what the API answered: the approval history of the template, the approval history
 * of the review records of its change requests, and which approval reviewed which version. It decides nothing, and it draws
 * nothing while there is nothing to show.
 */
export function TemplateApprovals({
  templateId,
  lifecycle,
  requests,
  refresh,
}: {
  templateId: string;
  lifecycle: TemplateLifecycleView | undefined;
  requests: ChangeRequestsView | undefined;
  refresh: number;
}) {
  const reviewed = (requests?.requests ?? []).filter((request) => request.reviewId).slice(0, MOST_REQUESTS);
  const reviewIds = reviewed.map((request) => request.reviewId as string);
  const templateHistory = useApprovalHistory(TEMPLATE_OBJECT, [templateId], refresh);
  const requestHistory = useApprovalHistory(REQUEST_OBJECT, reviewIds, refresh);

  const versionOf = new Map((lifecycle?.versionReviews ?? []).map((one) => [one.requestId, one.versionNo]));
  const entries: ApprovalHistoryEntry[] = [];
  for (const instance of templateHistory[templateId] ?? []) {
    const version = versionOf.get(instance.summary.id);
    entries.push({
      key: instance.summary.id,
      at: instance.summary.startedAt,
      title: version === undefined ? "Approval" : version === 1 ? "New template" : `New version ${version}`,
      instance,
      note: version === undefined || version === 1 ? undefined : `Version ${version}`,
    });
  }
  for (const request of reviewed) {
    const latest = requestHistory[request.reviewId as string]?.[0];
    if (latest) {
      const became = becameWords(request);
      entries.push({
        key: latest.summary.id,
        at: latest.summary.startedAt,
        title: "Change requested",
        instance: latest,
        note: became ? `${request.title}. Turned into ${became}.` : request.title,
      });
    }
  }
  if (entries.length === 0) {
    return null;
  }
  return (
    <aside className="detail-flow-column" aria-label="Approvals of this template" data-testid="template-approvals">
      <ApprovalHistoryList entries={entries} label="Approvals of this template" />
    </aside>
  );
}
