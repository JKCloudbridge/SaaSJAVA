"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { Modal } from "@/components/ui/Modal";
import { ageText, personText } from "@/lib/approvals/words";
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

/** Where a record is opened: the page its owner has for it (the server says), else the platform's own record page. */
function recordHref(task: Task): string {
  const { objectApiName, recordId } = task.instance;
  return task.path ?? `/records/${encodeURIComponent(objectApiName)}/${encodeURIComponent(recordId)}`;
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
  // The setup screen links here narrowed by object and process; there is no filter form.
  const object = search.get("object") ?? "";
  const process = search.get("process") ?? "";
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

  const decidedTab = tab === "DECIDED";
  const lastStepOf = (task: Task) =>
    task.instance.currentStep !== undefined &&
    task.instance.currentStep !== null &&
    task.instance.currentStep === task.instance.stepCount;

  return (
    <>
      <ListPage<Task>
        title="Approvals"
        testId="approval-inbox"
        rowTestId="inbox-task"
        items={tasks}
        failure={failure}
        rowKey={(task) => task.assignmentId}
        primaryHeader="Name"
        primary={(task) =>
          (task.recordReadable && task.recordTitle ? task.recordTitle : task.objectLabel) ??
          (task.recordReadable ? "Record" : "A record")
        }
        sortPrimary
        openHref={recordHref}
        canOpen={(task) => task.recordReadable}
        primaryExtra={(task) =>
          task.recordReadable ? null : (
            <span className="hint" data-testid="inbox-unreadable">
              You cannot open this record. Approving it gives you no access to it.
            </span>
          )
        }
        columns={[
          {
            key: "asked-by",
            header: "Asked by",
            kind: "muted",
            cell: (task) => personText(task.instance.submittedBy),
            sort: (task) => personText(task.instance.submittedBy),
          },
          {
            key: "time",
            header: "Time",
            kind: "muted",
            cell: (task) => (task.since ? `${ageText(task.since)} ago` : ""),
            sort: (task) => task.since ?? "",
          },
          decidedTab
            ? {
                key: "outcome",
                header: "Outcome",
                kind: "tag",
                cell: (task) => <Tag>{humanize(task.status)}</Tag>,
              }
            : {
                key: "actions",
                header: "Actions",
                kind: "actions",
                cell: (task) => (
                  <>
                    {task.actions.canApprove ? (
                      <button type="button" className="button" onClick={() => setActing({ task, kind: "approve" })}>
                        Approve
                      </button>
                    ) : null}{" "}
                    {task.actions.canReject ? (
                      <button
                        type="button"
                        className="button button-danger"
                        onClick={() => setActing({ task, kind: "reject" })}
                      >
                        Reject
                      </button>
                    ) : null}{" "}
                    {task.actions.canReassign ? (
                      <button
                        type="button"
                        className="button button-secondary"
                        onClick={() => setActing({ task, kind: "reassign" })}
                      >
                        Hand it on
                      </button>
                    ) : null}
                  </>
                ),
              },
        ]}
        searchText={(task) => `${task.recordTitle ?? ""} ${task.objectLabel ?? ""} ${personText(task.instance.submittedBy)}`}
        tools={
          <Link className="button button-secondary" href="/approvals/delegations">
            Delegations
          </Link>
        }
        notices={
          notice ? (
            <p role="status" data-testid="inbox-notice">
              {notice}
            </p>
          ) : undefined
        }
        above={
          <div className="detail-tabs-row">
            <div role="tablist" aria-label="Approvals" className="detail-tabs">
              {TABS.map((one) => (
                <button
                  key={one.code}
                  type="button"
                  role="tab"
                  className="detail-tab"
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
          </div>
        }
        below={
          next ? (
            <p>
              <button
                type="button"
                className="button button-secondary"
                onClick={() => void load(next)}
                data-testid="inbox-more"
              >
                Show more
              </button>
            </p>
          ) : undefined
        }
        emptyText="Nothing here."
      />
      {acting ? (
        <Modal
          title={acting.kind === "approve" ? "Approve" : acting.kind === "reject" ? "Reject" : "Hand it on"}
          onClose={() => setActing(undefined)}
        >
          <ApprovalActionForm
            kind={acting.kind}
            requestId={acting.task.instance.id}
            version={acting.task.instance.version}
            explain={explain(acting.task, acting.kind)}
            asksForChoice={acting.task.instance.currentStepAsksForChoice}
            lastStep={lastStepOf(acting.task)}
            objectApiName={acting.task.instance.objectApiName}
            recordId={acting.task.instance.recordId}
            onClose={() => setActing(undefined)}
            onDone={(text) => {
              setActing(undefined);
              setNotice(text);
              setTasks(undefined);
              void load();
            }}
          />
        </Modal>
      ) : null}
    </>
  );
}
