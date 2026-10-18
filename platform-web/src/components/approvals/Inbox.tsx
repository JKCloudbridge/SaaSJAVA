"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ageText, personText, reasonText } from "@/lib/approvals/words";
import { humanize } from "@/lib/builder/approvalModel";
import { call } from "@/lib/records/call";
import { ApprovalActionForm, type ApprovalActionKind } from "./ApprovalActionForm";

type Task = components["schemas"]["ApprovalTaskView"];

/** The tabs of the inbox: the words are the screen's; the codes are the API's parameter. */
const TABS = [
  { code: "TO_APPROVE", label: "To approve" },
  { code: "DELEGATED", label: "Delegated to me" },
  { code: "DECIDED", label: "Decided" },
] as const;

/** Where a record is opened, inside the platform's own record pages. */
function recordHref(task: Task): string {
  return `/records/${encodeURIComponent(task.instance.objectApiName)}/${encodeURIComponent(task.instance.recordId)}`;
}

/**
 * The inbox of an approver (Sprint 24, ADR-0152): the tasks the signed-in member holds, in three tabs, narrowed by object
 * and process, a page at a time. Each row names the record by its title only when the API says the member can read it and
 * says so plainly when they cannot (an approval grants no access); the buttons are the ones the API says are open for the
 * task. Approving and rejecting are one at a time, with a confirmation that says what will happen and a comment (required to
 * reject); there is no bulk approval. The screen decides nothing: the version of the request goes with the call and the
 * API's own words come back for a refusal.
 */
export function Inbox() {
  const search = useSearchParams();
  const [tab, setTab] = useState<string>(search.get("tab") ?? TABS[0].code);
  const [object, setObject] = useState(search.get("object") ?? "");
  const [process, setProcess] = useState(search.get("process") ?? "");
  const [tasks, setTasks] = useState<Task[] | undefined>();
  const [next, setNext] = useState<string | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [acting, setActing] = useState<{ task: Task; kind: ApprovalActionKind } | undefined>();

  const load = useCallback(
    async (cursor?: string) => {
      const outcome = await call(() =>
        api.GET("/api/v1/approvals/inbox", {
          params: {
            query: {
              tab,
              object: object.trim() === "" ? undefined : object.trim(),
              process: process.trim() === "" ? undefined : process.trim(),
              cursor,
            },
          },
        }),
      );
      if (!outcome.ok) {
        setFailure(outcome.failure.message);
        return;
      }
      setFailure(undefined);
      setTasks((known) => (cursor && known ? [...known, ...outcome.data.data] : outcome.data.data));
      setNext(outcome.data.pagination.hasMore ? outcome.data.pagination.nextCursor : undefined);
    },
    [tab, object, process],
  );

  useEffect(() => {
    void Promise.resolve().then(() => load());
  }, [load]);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="inbox-failed">
        {failure}
      </p>
    );
  }

  function explain(task: Task, kind: ApprovalActionKind): string {
    const last = task.instance.currentStep === task.instance.stepCount;
    const title = task.recordReadable && task.recordTitle ? `“${task.recordTitle}”` : "this record";
    switch (kind) {
      case "approve":
        return last
          ? `You approve ${title} at its last step (${task.stepLabel}). The request is approved, the record is unlocked and ` +
              `the process may set fields of it.`
          : `You approve ${title} at the step ${task.stepLabel}. If your approval completes the step, the request goes on ` +
              `to the next step; the record stays locked until the request ends.`;
      case "reject":
        return `You reject ${title}. This ends the whole request, unlocks the record and tells nobody else to decide.`;
      default:
        return `You hand your task for ${title} to one other person. You are no longer asked about it.`;
    }
  }

  return (
    <section aria-labelledby="inbox-heading" data-testid="approval-inbox" className="stack">
      <div className="list-head">
        <h1 id="inbox-heading">Approvals</h1>
        <Link className="button button-secondary" href="/approvals/delegations">
          Delegations
        </Link>
      </div>
      <div className="control-tabs" role="tablist" aria-label="Approvals">
        {TABS.map((one) => (
          <button
            key={one.code}
            type="button"
            role="tab"
            aria-selected={tab === one.code}
            onClick={() => {
              setTasks(undefined);
              setActing(undefined);
              setTab(one.code);
            }}
          >
            {one.label}
          </button>
        ))}
      </div>
      <form
        className="filters"
        aria-label="Narrow the list"
        onSubmit={(event) => {
          event.preventDefault();
          setTasks(undefined);
          void load();
        }}
      >
        <label htmlFor="inbox-object">Object</label>
        <input id="inbox-object" value={object} onChange={(event) => setObject(event.target.value)} />
        <label htmlFor="inbox-process">Process</label>
        <input id="inbox-process" value={process} onChange={(event) => setProcess(event.target.value)} />
        <button type="submit" className="button button-secondary">
          Narrow
        </button>
      </form>
      {notice ? (
        <p role="status" data-testid="inbox-notice">
          {notice}
        </p>
      ) : null}
      {!tasks ? (
        <p aria-live="polite">Loading…</p>
      ) : tasks.length === 0 ? (
        <p data-testid="inbox-empty">Nothing here.</p>
      ) : (
        <ul className="stack" data-testid="inbox-tasks">
          {tasks.map((task) => (
            <li key={task.assignmentId} className="card" data-testid="inbox-task">
              <div className="list-head">
                <strong>
                  {task.recordReadable ? (
                    <Link href={recordHref(task)}>{task.recordTitle ? task.recordTitle : task.objectLabel ?? "Record"}</Link>
                  ) : (
                    <span>{task.objectLabel ?? "A record"}</span>
                  )}
                </strong>
                <span className="badge">{humanize(task.status)}</span>
              </div>
              {!task.recordReadable ? (
                <p className="hint" data-testid="inbox-unreadable">
                  You cannot open this record. Approving it does not give you access to it; an administrator can change that.
                </p>
              ) : null}
              <p className="hint">
                {task.instance.processLabel} · {task.stepLabel} (step {task.instance.currentStep ?? "—"} of{" "}
                {task.instance.stepCount}) · asked {ageText(task.since)} ago
                {task.forPerson ? ` · ${humanize(task.via).toLowerCase()}: ${personText(task.forPerson)}` : ""}
              </p>
              {task.instance.attention ? <p className="hint">{reasonText(task.instance.attention)}</p> : null}
              <div className="filters-actions" role="group" aria-label="Task actions">
                {task.actions.canApprove ? (
                  <button type="button" className="button" onClick={() => setActing({ task, kind: "approve" })}>
                    Approve
                  </button>
                ) : null}
                {task.actions.canReject ? (
                  <button type="button" className="button button-danger" onClick={() => setActing({ task, kind: "reject" })}>
                    Reject
                  </button>
                ) : null}
                {task.actions.canReassign ? (
                  <button type="button" className="button button-secondary" onClick={() => setActing({ task, kind: "reassign" })}>
                    Hand it on
                  </button>
                ) : null}
              </div>
              {acting && acting.task.assignmentId === task.assignmentId ? (
                <ApprovalActionForm
                  kind={acting.kind}
                  requestId={task.instance.id}
                  version={task.instance.version}
                  explain={explain(task, acting.kind)}
                  onClose={() => setActing(undefined)}
                  onDone={(text) => {
                    setActing(undefined);
                    setNotice(text);
                    setTasks(undefined);
                    void load();
                  }}
                />
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {next ? (
        <p>
          <button type="button" className="button button-secondary" onClick={() => void load(next)} data-testid="inbox-more">
            Show more
          </button>
        </p>
      ) : null}
    </section>
  );
}
