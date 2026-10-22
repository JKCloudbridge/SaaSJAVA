"use client";

import { useCallback, useEffect, useState } from "react";
import { ApprovalActionForm, type ApprovalActionKind } from "@/components/approvals/ApprovalActionForm";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { historyText, personText, reasonText } from "@/lib/approvals/words";
import { humanize } from "@/lib/builder/approvalModel";
import { call } from "@/lib/records/call";

type PanelView = components["schemas"]["RecordApprovalView"];
type InstanceView = components["schemas"]["ApprovalInstanceView"];

/** What the record page needs to know about the approval to decide what to draw around it. */
export interface ApprovalState {
  /** The record is locked by a pending request. */
  locked: boolean;
  /** The member may change a locked record anyway (the server's `canCancel` is the ability to override). */
  mayOverride: boolean;
  /** The member holds an open task they may approve now (the server's `canApprove`; FA4 uses it to say what an approval gives). */
  mayApprove: boolean;
}

/**
 * The approval panel of one record (Sprint 24, ADR-0152): whether the record is locked, the request that is pending or the
 * last one that ended with its steps, the people asked and, for those who may see it, the history; and the buttons the
 * server says are open for this member right now (`actions`). The panel draws only when the object has a process or the
 * record has a request. It decides nothing: every button's call is judged again by the API, a refusal is shown in the API's
 * words, and a request that changed while the page was open is answered "changed by someone else" and reloaded.
 */
export function ApprovalPanel({
  objectApiName,
  recordId,
  recordVersion,
  refresh,
  onState,
  onChanged,
}: {
  objectApiName: string;
  recordId: string;
  /** The version of the record this page shows; an update made again is judged on it. */
  recordVersion: number;
  /** Changes when the record page wants the panel read again (for example after the record was saved). */
  refresh: number;
  onState: (state: ApprovalState) => void;
  /** An action of the panel may have changed the record (an outcome update) or its lock: read them again. */
  onChanged: () => void;
}) {
  const [panel, setPanel] = useState<PanelView | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const [acting, setActing] = useState<ApprovalActionKind | "submit" | undefined>();
  const [process, setProcess] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const outcome = await call(() =>
      api.GET("/api/v1/objects/{objectApiName}/records/{id}/approval", {
        params: { path: { objectApiName, id: recordId } },
      }),
    );
    if (!outcome.ok) {
      setFailure(outcome.failure.message);
      return;
    }
    setFailure(undefined);
    setPanel(outcome.data.data);
    onState({
      locked: outcome.data.data.locked,
      mayOverride: outcome.data.data.actions.canCancel,
      mayApprove: outcome.data.data.actions.canApprove,
    });
    setProcess((chosen) => chosen || outcome.data.data.processes[0]?.apiName || "");
  }, [objectApiName, recordId, onState]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load, refresh]);

  async function submit() {
    setBusy(true);
    setFailure(undefined);
    const outcome = await call(() =>
      api.POST("/api/v1/objects/{objectApiName}/records/{id}/approval", {
        params: { path: { objectApiName, id: recordId } },
        body: { process: process === "" ? undefined : process, comment: comment.trim() === "" ? undefined : comment.trim() },
      }),
    );
    setBusy(false);
    if (outcome.ok) {
      setActing(undefined);
      setComment("");
      setNotice("The record was submitted for approval and is locked until the request ends.");
      await load();
      onChanged();
    } else {
      setFailure(outcome.failure.message);
    }
  }

  if (failure && !panel) {
    return (
      <p className="form-message" role="alert" data-testid="approval-panel-failed">
        {failure}
      </p>
    );
  }
  if (!panel || !panel.available) {
    return null;
  }
  const current: InstanceView | undefined = panel.current;
  const summary = current?.summary;
  const actions = panel.actions;

  function explain(kind: ApprovalActionKind): string {
    switch (kind) {
      case "approve":
        return "You approve this record at the step it is waiting at. If your approval completes the step, the request goes on or ends; the record stays locked until it ends.";
      case "reject":
        return "You reject this record. This ends the whole request and unlocks the record.";
      case "recall":
        return "You withdraw the request. The record is unlocked and the process may set fields of it.";
      case "cancel":
        return "You end the request as an administrator. The record is unlocked.";
      case "assign":
        return "You ask one more person to decide the step that is waiting. The step is no longer left without anybody.";
      default:
        return "You hand your task to one other person. You are no longer asked about it.";
    }
  }

  const done = (text: string) => {
    setActing(undefined);
    setNotice(text);
    void load();
    onChanged();
  };

  return (
    <section className="card stack" aria-labelledby="approval-heading" data-testid="approval-panel">
      <div className="list-head">
        <h2 id="approval-heading">Approval</h2>
        {summary ? <span className="badge">{humanize(summary.status)}</span> : null}
      </div>

      {panel.locked ? (
        <p role="status" className="hint" data-testid="approval-locked">
          This record is locked while it is being approved. It cannot be changed or removed until the approval ends
          {actions.canCancel ? ", except by you, who may override the lock" : ""}.
        </p>
      ) : null}
      {notice ? (
        <p role="status" data-testid="approval-notice">
          {notice}
        </p>
      ) : null}
      {failure ? (
        <p className="form-message" role="alert" data-testid="approval-panel-problem">
          {failure}
        </p>
      ) : null}

      {summary?.attention ? (
        <p className="form-message" role="alert" data-testid="approval-attention">
          {reasonText(summary.attention)}
        </p>
      ) : null}
      {summary?.outcomeFailure ? (
        <p className="form-message" role="alert" data-testid="approval-outcome-failed">
          The decision stands, but the record could not be updated: {reasonText(summary.outcomeFailure)}
        </p>
      ) : null}

      {current && summary ? (
        <>
          <p>
            <strong>{summary.processLabel}</strong>
            {summary.currentStepLabel ? ` · waiting at ${summary.currentStepLabel}` : ""} · submitted by{" "}
            {personText(summary.submittedBy)}
          </p>
          <ol className="stack" data-testid="approval-steps">
            {current.steps.map((step) => (
              <li key={step.position}>
                <strong>{step.label}</strong> <span className="badge">{humanize(step.status)}</span>
                {step.assignments.length > 0 ? (
                  <ul>
                    {step.assignments.map((task) => (
                      <li key={task.id}>
                        {personText(task.assignee)}
                        {task.forPerson ? ` (for ${personText(task.forPerson)})` : ""} — {humanize(task.status).toLowerCase()}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </li>
            ))}
          </ol>
        </>
      ) : null}

      <div className="filters-actions" role="group" aria-label="Approval actions">
        {actions.canSubmit ? (
          <button type="button" className="button" onClick={() => setActing("submit")} data-testid="approval-submit">
            Submit for approval
          </button>
        ) : null}
        {actions.canApprove ? (
          <button type="button" className="button" onClick={() => setActing("approve")}>
            Approve
          </button>
        ) : null}
        {actions.canReject ? (
          <button type="button" className="button button-danger" onClick={() => setActing("reject")}>
            Reject
          </button>
        ) : null}
        {actions.canReassign ? (
          <button type="button" className="button button-secondary" onClick={() => setActing("reassign")}>
            Hand my task on
          </button>
        ) : null}
        {actions.canAssign ? (
          <button type="button" className="button button-secondary" onClick={() => setActing("assign")}>
            Ask someone as well
          </button>
        ) : null}
        {actions.canRecall ? (
          <button type="button" className="button button-secondary" onClick={() => setActing("recall")}>
            Withdraw the request
          </button>
        ) : null}
        {actions.canCancel ? (
          <button type="button" className="button button-danger" onClick={() => setActing("cancel")}>
            End the request
          </button>
        ) : null}
      </div>
      {!actions.canSubmit && panel.notSubmittableReason && !panel.locked ? (
        <p className="hint" data-testid="approval-not-submittable">
          {reasonText(panel.notSubmittableReason)}
        </p>
      ) : null}

      {acting === "submit" ? (
        <form
          className="stack card"
          aria-label="Submit for approval"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <p data-testid="approval-explain">
            The record is locked from this moment until the approval ends: nobody can change or remove it, you included.
            The people the process names are asked in turn.
          </p>
          {panel.processes.length > 1 ? (
            <div className="field">
              <label htmlFor="approval-process">Process</label>
              <select id="approval-process" value={process} onChange={(event) => setProcess(event.target.value)}>
                {panel.processes.map((one) => (
                  <option key={one.apiName} value={one.apiName}>
                    {one.label}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <p>{panel.processes[0]?.label}</p>
          )}
          <div className="field">
            <label htmlFor="approval-comment">Note to the people asked (optional)</label>
            <textarea
              id="approval-comment"
              rows={3}
              maxLength={2000}
              value={comment}
              onChange={(event) => setComment(event.target.value)}
            />
          </div>
          <div className="filters-actions">
            <button type="submit" className="button" disabled={busy}>
              Submit and lock the record
            </button>
            <button type="button" className="link-button" disabled={busy} onClick={() => setActing(undefined)}>
              Not now
            </button>
          </div>
        </form>
      ) : null}
      {acting && acting !== "submit" && summary ? (
        <ApprovalActionForm
          kind={acting}
          requestId={summary.id}
          version={summary.version}
          explain={explain(acting)}
          onClose={() => setActing(undefined)}
          onDone={done}
        />
      ) : null}
      {actions.canReapply && summary ? (
        <ReapplyButton requestId={summary.id} version={summary.version} recordVersion={recordVersion} onDone={done} />
      ) : null}

      {current?.historyVisible ? (
        <details data-testid="approval-history">
          <summary>History</summary>
          <ol className="stack">
            {current.history.map((line) => (
              <li key={line.id}>
                <strong>{historyText(line.kind)}</strong>
                {line.stepPosition ? ` · step ${line.stepPosition}` : ""}
                {` · ${personText(line.actor)}`}
                {line.onBehalfOf ? ` for ${personText(line.onBehalfOf)}` : ""}
                {line.target ? ` → ${personText(line.target)}` : ""}
                {line.reason ? ` · ${reasonText(line.reason)}` : ""} · {new Date(line.at).toLocaleString()}
                {line.comment ? <blockquote data-testid="approval-comment">{line.comment}</blockquote> : null}
              </li>
            ))}
          </ol>
        </details>
      ) : null}
    </section>
  );
}

/** Makes the outcome updates again, on the record version this page shows: a record that changed since is refused (ADR-0149). */
function ReapplyButton({
  requestId,
  version,
  recordVersion,
  onDone,
}: {
  requestId: string;
  version: number;
  recordVersion: number;
  onDone: (text: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>();
  return (
    <div className="stack" data-testid="approval-reapply">
      <p className="hint">
        The update is made on the record as it is now. If someone changed the record since this page was read, it is refused.
      </p>
      {failure ? (
        <p className="form-message" role="alert">
          {failure}
        </p>
      ) : null}
      <button
        type="button"
        className="button button-secondary"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          setFailure(undefined);
          const outcome = await call(() =>
            api.POST("/api/v1/approvals/instances/{id}/outcome", {
              params: { path: { id: requestId } },
              body: { recordVersion, version },
            }),
          );
          setBusy(false);
          if (outcome.ok) {
            onDone("The update was made again.");
          } else {
            setFailure(outcome.failure.message);
          }
        }}
      >
        Make the update again
      </button>
    </div>
  );
}
