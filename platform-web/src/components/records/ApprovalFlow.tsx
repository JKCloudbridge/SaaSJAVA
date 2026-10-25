"use client";

import { approvalFlow, type FlowTone } from "@/lib/approvals/flow";
import type { components } from "@/lib/api/generated/schema";
import type { RecordApproval } from "./ApprovalPanel";
import { Tag, type TagTone } from "@/components/ui/lists/Tag";

type Instance = components["schemas"]["ApprovalInstanceView"];

const TAG_TONES: Record<FlowTone, TagTone> = { current: "on", done: "ok", rejected: "error", muted: "quiet" };

/** The id of the history in the approval card, which the "History" link opens. */
export const APPROVAL_HISTORY_ID = "approval-history";

function showHistory() {
  const history = document.getElementById(APPROVAL_HISTORY_ID);
  if (history instanceof HTMLDetailsElement) {
    history.open = true;
  }
  history?.scrollIntoView?.({ block: "nearest" });
}

/**
 * A thin vertical flow of one approval request (FA6D, FA6E): who submitted it, then one line per step with its state and
 * one line naming the person who decided it (or who it waits for). A dot marks where the request is now. It is a reading
 * aid made from what the API answered: it decides nothing. The same drawing serves the card of a record (the latest
 * request), the history of a template (every request, a template's and its change requests') and a change request.
 *
 * @param showHistoryLink draws the "History" link that opens the approval card's history; off for a flow drawn inside an
 *        entry of the history, which has no card to open
 * @param heading the title of the flow; "Approval" by default
 */
export function ApprovalFlowView({
  instance,
  showHistoryLink = true,
  heading = "Approval",
  headingLevel = 2,
}: {
  instance: Instance;
  showHistoryLink?: boolean;
  heading?: string | null;
  headingLevel?: 2 | 3 | 4;
}) {
  const flow = approvalFlow(instance);
  const Heading = `h${headingLevel}` as "h2" | "h3" | "h4";
  return (
    <nav className="approval-flow" aria-label={heading === null || heading === "Approval" ? "Approval flow" : heading} data-testid="approval-flow">
      {heading !== null || (showHistoryLink && instance.historyVisible) ? (
        <div className="approval-flow-head">
          {heading !== null ? <Heading className="approval-flow-title">{heading}</Heading> : <span />}
          {showHistoryLink && instance.historyVisible ? (
            <button type="button" className="link-button" onClick={showHistory}>
              History
            </button>
          ) : null}
        </div>
      ) : null}
      <ol className="approval-flow-steps">
        {flow.map((step) => (
          <li
            key={step.key}
            className={`approval-flow-step approval-flow-${step.tone}${step.current ? " approval-flow-now" : ""}`}
            aria-current={step.current ? "step" : undefined}
          >
            <span className="approval-flow-dot" aria-hidden="true" />
            <div className="approval-flow-body">
              <div className="approval-flow-line">
                <span className="approval-flow-label">{step.label}</span>
                <Tag tone={TAG_TONES[step.tone]}>{step.state}</Tag>
              </div>
              {step.person !== "" ? <div className="approval-flow-person hint">{step.person}</div> : null}
            </div>
          </li>
        ))}
      </ol>
    </nav>
  );
}

/**
 * The flow of the latest approval request of a record, from the record's approval panel. It draws nothing when the record
 * has never had a request. The full detail, the decisions and the history stay in the approval card.
 */
export function ApprovalFlow({ approval }: { approval: RecordApproval }) {
  const current = approval.panel?.available ? approval.panel.current : undefined;
  if (!current) {
    return null;
  }
  return <ApprovalFlowView instance={current} />;
}
