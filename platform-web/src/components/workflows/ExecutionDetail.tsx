"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { humanize } from "@/lib/builder/workflowModel";
import { useObjectForm } from "@/lib/records/hooks";
import { useSession } from "@/lib/session/SessionProvider";
import {
  offersCancel,
  offersReplay,
  reasonText,
  replayConfirmation,
  replayResultText,
  statusText,
  stepStatusText,
} from "@/lib/workflows/runsModel";

type Execution = components["schemas"]["WorkflowExecutionView"];
type ReplayResult = components["schemas"]["WorkflowReplayResultView"];

/**
 * One run with its steps (Sprint 23, ADR-0141 to ADR-0143): what started it, each step with its attempts, when the next
 * attempt is due, the reason and the type of the last failure in words. The record is named by its title and linked, read
 * with the person's own permissions; a person who may not open it sees no title. Replay and Cancel appear only when the
 * API's list of the caller's abilities names them, and Replay says exactly what it will do first.
 */
export function ExecutionDetail() {
  const params = useParams<{ executionId: string }>();
  const executionId = params.executionId;
  const { state: session } = useSession();
  const abilities = session.status === "signedIn" ? session.user.abilities : [];
  const [execution, setExecution] = useState<Execution | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<ReplayResult | undefined>();

  const reload = useCallback(async () => {
    try {
      const found = await api.GET("/api/v1/workflows/executions/{executionId}", { params: { path: { executionId } } });
      if (!found.data) {
        setFailure(await failureText(found.error, found.response));
        return;
      }
      setFailure(undefined);
      setExecution(found.data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [executionId]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="run-failed">
        {failure}
      </p>
    );
  }
  if (!execution) {
    return <p aria-live="polite">Loading…</p>;
  }
  const run: Execution = execution;

  function replay() {
    setConfirming(false);
    void act(async () => {
      const answer = await api.POST("/api/v1/workflows/executions/{executionId}/replay", {
        params: { path: { executionId: run.id } },
        body: {},
      });
      setResult(answer.data?.data);
      return {
        error: answer.error,
        response: answer.response,
        text: answer.data ? replayResultText(answer.data.data) : undefined,
      };
    });
  }

  function cancel() {
    void act(async () => {
      const answer = await api.POST("/api/v1/workflows/executions/{executionId}/cancel", {
        params: { path: { executionId: run.id } },
      });
      return { error: answer.error, response: answer.response, text: "The run was cancelled." };
    });
  }

  return (
    <section aria-labelledby="run-heading" className="stack" data-testid="run">
      <nav aria-label="Where you are" className="breadcrumb">
        <Link href="/workflows/executions">Workflow runs</Link>
        <span aria-hidden="true"> / </span>
        <span aria-current="page">{run.workflow}</span>
      </nav>
      <h2 id="run-heading">
        {run.workflow} <span className="badge">{statusText(run.status)}</span>
      </h2>
      {run.reason ? (
        <p data-testid="run-reason">
          {reasonText(run.reason)}
          {run.failureType ? <span className="hint"> ({run.failureType})</span> : null}
        </p>
      ) : null}
      <ActionMessages notice={notice} problem={problem} testId="run" />

      <dl className="facts">
        <dt>Record</dt>
        <dd>
          <RecordReference objectApiName={run.objectApiName} recordId={run.recordId} />
        </dd>
        <dt>Started by</dt>
        <dd>{humanize(run.trigger)}</dd>
        <dt>Version of the workflow</dt>
        <dd>{run.workflowVersion}</dd>
        <dt>Queued</dt>
        <dd>{new Date(run.queuedAt).toLocaleString()}</dd>
        {run.startedAt ? (
          <>
            <dt>Started</dt>
            <dd>{new Date(run.startedAt).toLocaleString()}</dd>
          </>
        ) : null}
        {run.finishedAt ? (
          <>
            <dt>Ended</dt>
            <dd>{new Date(run.finishedAt).toLocaleString()}</dd>
          </>
        ) : null}
        {run.status === "QUEUED" ? (
          <>
            <dt>Due</dt>
            <dd>{new Date(run.runAt).toLocaleString()}</dd>
          </>
        ) : null}
        <dt>Workflows in a row before it</dt>
        <dd>{run.chainDepth}</dd>
        {run.replayOf ? (
          <>
            <dt>Replays</dt>
            <dd>
              <Link href={`/workflows/executions/${run.replayOf}`}>The earlier run</Link>, resuming at step{" "}
              {run.resumeStep}
            </dd>
          </>
        ) : null}
        {run.replayedBy ? (
          <>
            <dt>Replayed by</dt>
            <dd>
              <Link href={`/workflows/executions/${run.replayedBy}`}>A newer run</Link>
            </dd>
          </>
        ) : null}
      </dl>

      {offersReplay(run) || offersCancel(run) ? (
        <div className="card" role="group" aria-label="What can be done with this run" data-testid="run-actions">
          {abilities.includes("workflows.replay") && offersReplay(run) ? (
            confirming ? (
              <>
                <p data-testid="replay-confirmation">{replayConfirmation(run)}</p>
                <button type="button" className="button" disabled={busy} onClick={replay}>
                  Yes, replay it
                </button>{" "}
                <button type="button" className="link-button" onClick={() => setConfirming(false)}>
                  Not now
                </button>
              </>
            ) : (
              <button type="button" className="button" disabled={busy} onClick={() => setConfirming(true)}>
                Replay
              </button>
            )
          ) : null}{" "}
          {abilities.includes("workflows.manage") && offersCancel(run) ? (
            <button type="button" className="button button-danger" disabled={busy} onClick={cancel}>
              Cancel the run
            </button>
          ) : null}
        </div>
      ) : null}
      {result?.replay ? (
        <p role="status" data-testid="replay-made">
          <Link href={`/workflows/executions/${result.replay}`}>See the new run</Link>
        </p>
      ) : null}

      <h3>Steps</h3>
      {(run.steps ?? []).length === 0 ? (
        <p className="hint">No step has started.</p>
      ) : (
        <table className="table" data-testid="steps">
          <thead>
            <tr>
              <th>Step</th>
              <th>Action</th>
              <th>State</th>
              <th>Attempts</th>
              <th>Last failure</th>
            </tr>
          </thead>
          <tbody>
            {(run.steps ?? []).map((step) => (
              <tr key={step.position} data-testid="step-row">
                <td>
                  {step.position}. {step.name}
                </td>
                <td>{humanize(step.type)}</td>
                <td>
                  {stepStatusText(step.status)}
                  {step.nextAttemptAt ? (
                    <div className="hint">Next attempt {new Date(step.nextAttemptAt).toLocaleString()}</div>
                  ) : null}
                </td>
                <td>{step.attempts}</td>
                <td>
                  {step.reason ? <div>{reasonText(step.reason)}</div> : null}
                  {step.failureType ? <div className="hint">{step.failureType}</div> : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/**
 * The record a run is about: its title when the person may open it (read with their own permissions), and a link either
 * way. It shows the title and nothing else of the record.
 */
function RecordReference({ objectApiName, recordId }: { objectApiName: string; recordId: string }) {
  const { state } = useObjectForm(objectApiName);
  const [title, setTitle] = useState<string | undefined>();
  const titleField = state.status === "ready" ? state.form.titleField : undefined;
  const label = state.status === "ready" ? state.form.label : objectApiName;

  useEffect(() => {
    if (!titleField) {
      return;
    }
    let live = true;
    void api
      .GET("/api/v1/objects/{objectApiName}/records/{id}", { params: { path: { objectApiName, id: recordId } } })
      .then((answer) => {
        const value = answer.data?.data.fields[titleField];
        if (live && typeof value === "string") {
          setTitle(value);
        }
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [objectApiName, recordId, titleField]);

  return (
    <Link href={`/records/${encodeURIComponent(objectApiName)}/${recordId}`}>
      {title ? `${label}: ${title}` : `Open the ${label}`}
    </Link>
  );
}
