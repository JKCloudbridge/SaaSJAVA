"use client";

import { useId, useState, type ReactNode } from "react";
import type { components } from "@/lib/api/generated/schema";
import { ApprovalFlowView } from "./ApprovalFlow";

type Instance = components["schemas"]["ApprovalInstanceView"];

/** One approval in the history: when it began, what it was about, and the request itself. */
export interface ApprovalHistoryEntry {
  key: string;
  /** When the approval began (an instant); the entries are shown newest first by it. */
  at: string;
  /** A short title: "New template", "Change requested", … */
  title: string;
  instance: Instance;
  /** A quiet line under the title once opened (for example the version a change request became). */
  note?: ReactNode;
}

/**
 * The approvals of a record as an accordion by date (FA6E): one entry for each approval, newest first, each with its date and
 * a short title, and when opened the same flow the card draws for the latest request. The newest entry is open at first.
 * Every header is a real button that says whether its entry is open (`aria-expanded`) and what it controls. It is made from
 * what the API answered and decides nothing.
 */
export function ApprovalHistoryList({ entries, label = "Approvals" }: { entries: readonly ApprovalHistoryEntry[]; label?: string }) {
  const ids = useId();
  const ordered = [...entries].sort((a, b) => b.at.localeCompare(a.at));
  // Until the person opens or closes something, the newest entry is the one that is open.
  const [chosen, setChosen] = useState<Set<string> | null>(null);
  const open = (key: string, index: number) => (chosen === null ? index === 0 : chosen.has(key));
  const toggle = (key: string) =>
    setChosen((now) => {
      const next = new Set(now ?? (ordered[0] ? [ordered[0].key] : []));
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });

  if (ordered.length === 0) {
    return null;
  }
  return (
    <section className="approval-history" aria-label={label} data-testid="approval-history-list">
      {ordered.map((entry, index) => {
        const isOpen = open(entry.key, index);
        const headingId = `${ids}-${index}-heading`;
        const regionId = `${ids}-${index}-region`;
        return (
          <div className="approval-history-entry" key={entry.key} data-testid="approval-history-entry">
            <h3 className="approval-history-heading" id={headingId}>
              <button
                type="button"
                className="approval-history-toggle"
                aria-expanded={isOpen}
                aria-controls={regionId}
                onClick={() => toggle(entry.key)}
              >
                <span className="approval-history-title">{entry.title}</span>
                <span className="approval-history-date">{new Date(entry.at).toLocaleDateString()}</span>
              </button>
            </h3>
            <div id={regionId} role="region" aria-labelledby={headingId} hidden={!isOpen} className="approval-history-body">
              {isOpen ? (
                <>
                  {entry.note ? <p className="hint">{entry.note}</p> : null}
                  <ApprovalFlowView instance={entry.instance} showHistoryLink={false} heading={null} />
                </>
              ) : null}
            </div>
          </div>
        );
      })}
    </section>
  );
}

