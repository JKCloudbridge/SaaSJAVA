"use client";

import { useId, useState, type ReactNode } from "react";
import type { components } from "@/lib/api/generated/schema";
import { ApprovalFlowView } from "./ApprovalFlow";

type Instance = components["schemas"]["ApprovalInstanceView"];

/** One approval in the history: when it began, what it was about, and the request itself. */
export interface ApprovalHistoryEntry {
  key: string;
  /** When the approval began (an instant); the approvals are grouped by the reader's calendar day of it, newest first. */
  at: string;
  /** A short title: "New template", "Change requested", … */
  title: string;
  instance: Instance;
  /** A quiet line under the title (for example the version a change request became). */
  note?: ReactNode;
}

/** The reader's calendar day of an instant (their own time zone), as a sortable key. */
function dayOf(instant: string): string {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return instant;
  }
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

function dayWords(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : date.toLocaleDateString(undefined, { dateStyle: "medium" });
}

function timeWords(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

interface Day {
  key: string;
  words: string;
  approvals: ApprovalHistoryEntry[];
}

/**
 * The approvals of a record as an accordion by day (FA6E, FA11A): one item for each calendar day in the reader's time
 * zone, newest day first and open at first, older days closed. A day is one card; inside it each approval of that day, newest
 * first, tells its story in a few lines: a title, the time of day, a note, and the same flow the approval card draws (who
 * decided each step and the state). Every day header is a real button that says whether its day is open (`aria-expanded`) and
 * what it controls. It is made from what the API answered and decides nothing.
 */
export function ApprovalHistoryList({ entries, label = "Approvals" }: { entries: readonly ApprovalHistoryEntry[]; label?: string }) {
  const ids = useId();
  const ordered = [...entries].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
  const days: Day[] = [];
  for (const entry of ordered) {
    const key = dayOf(entry.at);
    const last = days[days.length - 1];
    if (last && last.key === key) {
      last.approvals.push(entry);
    } else {
      days.push({ key, words: dayWords(entry.at), approvals: [entry] });
    }
  }

  // Until the person opens or closes something, the newest day is the one that is open.
  const [chosen, setChosen] = useState<Set<string> | null>(null);
  const open = (key: string, index: number) => (chosen === null ? index === 0 : chosen.has(key));
  const toggle = (key: string) =>
    setChosen((now) => {
      const next = new Set(now ?? (days[0] ? [days[0].key] : []));
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });

  if (days.length === 0) {
    return null;
  }
  return (
    <section className="approval-history" aria-label={label} data-testid="approval-history-list">
      {days.map((day, index) => {
        const isOpen = open(day.key, index);
        const headingId = `${ids}-${index}-heading`;
        const regionId = `${ids}-${index}-region`;
        return (
          <div className="approval-history-entry" key={day.key} data-testid="approval-history-day">
            <h3 className="approval-history-heading" id={headingId}>
              <button
                type="button"
                className="approval-history-toggle"
                aria-expanded={isOpen}
                aria-controls={regionId}
                onClick={() => toggle(day.key)}
              >
                <span className="approval-history-title">{day.words}</span>
                <span className="approval-history-date">
                  {day.approvals.length === 1 ? "1 approval" : `${day.approvals.length} approvals`}
                </span>
              </button>
            </h3>
            <div id={regionId} role="region" aria-labelledby={headingId} hidden={!isOpen} className="approval-history-body">
              {isOpen ? (
                <ul className="approval-history-items">
                  {day.approvals.map((entry) => (
                    <li className="approval-history-item" key={entry.key} data-testid="approval-history-item">
                      <div className="approval-history-item-head">
                        <span className="approval-history-item-title">{entry.title}</span>
                        <time className="approval-history-date" dateTime={entry.at}>
                          {timeWords(entry.at)}
                        </time>
                      </div>
                      {entry.note ? <p className="hint">{entry.note}</p> : null}
                      <ApprovalFlowView instance={entry.instance} showHistoryLink={false} heading={null} />
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          </div>
        );
      })}
    </section>
  );
}
