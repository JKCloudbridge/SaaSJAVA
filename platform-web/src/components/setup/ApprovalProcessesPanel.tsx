"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ProcessView = components["schemas"]["ApprovalProcessView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

/**
 * The approval processes of the organization: the list, with the way into the builder where a process is made and changed
 * and the way to the requests under each. Switching on or off and removing are done here, live or, when a change set is
 * chosen, as a draft. The page shows what the API answers and in its words; a process that cannot be switched on or removed
 * says why (the plan has no approvals, requests are waiting for a decision).
 */
export function ApprovalProcessesPanel() {
  const { id: workingSet } = useWorkingSet();
  const router = useRouter();
  const [processes, setProcesses] = useState<ProcessView[] | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [objectApiName, setObjectApiName] = useState("");

  const reload = useCallback(async () => {
    try {
      const [list, objectList] = await Promise.all([
        api.GET("/api/v1/metadata/approvals"),
        api.GET("/api/v1/metadata/objects"),
      ]);
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setProcesses(list.data.data);
      setObjects(objectList.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="approvals-failed">
        {failure}
      </p>
    );
  }
  if (!processes) {
    return <p aria-live="polite">Loading…</p>;
  }

  function switchTo(process: ProcessView, active: boolean) {
    void act(async () => {
      if (workingSet) {
        const result = await addToSet(workingSet, {
          kind: "UPDATE_APPROVAL_PROCESS",
          objectApiName: process.objectApiName,
          itemApiName: process.apiName,
          updateApprovalProcess: {
            label: process.label,
            description: process.description,
            active,
            entryCriteria: process.entryCriteria,
            allowSelfApproval: process.allowSelfApproval,
            steps: process.steps,
            outcomes: process.outcomes,
            fallback: process.fallback,
            remindAfterHours: process.remindAfterHours,
            escalateAfterHours: process.escalateAfterHours,
            version: process.version,
          },
        });
        return {
          error: result.error,
          response: result.response,
          text: DRAFTED_TEXT,
          problem: problemText(result.error, result.response),
        };
      }
      const result = await api.POST("/api/v1/metadata/approvals/{objectApiName}/{processApiName}/activation", {
        params: { path: { objectApiName: process.objectApiName, processApiName: process.apiName } },
        body: { active, version: process.version },
      });
      return {
        error: result.error,
        response: result.response,
        text: active ? "The process is switched on." : "The process is switched off.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(process: ProcessView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "DELETE_APPROVAL_PROCESS",
            objectApiName: process.objectApiName,
            itemApiName: process.apiName,
          })
        : await api.DELETE("/api/v1/metadata/approvals/{objectApiName}/{processApiName}", {
            params: { path: { objectApiName: process.objectApiName, processApiName: process.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The process was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const labelOfObject = (name: string) => objects.find((one) => one.apiName === name)?.label ?? name;

  return (
    <section aria-labelledby="approvals-heading" data-testid="approval-processes">
      <div className="list-head">
        <h2 id="approvals-heading">Approval processes</h2>
        <Link className="button button-secondary" href="/approvals">
          Approvals inbox
        </Link>
      </div>
      <p className="hint">
        An approval process decides a record in steps: when a record is submitted it is locked, each step asks the people the
        process names, and when the request ends the record is unlocked and the process may set fields of it. A request that
        is under way keeps the version of the process it started under.
      </p>
      <form
        className="filters"
        aria-label="Start a new approval process"
        onSubmit={(event) => {
          event.preventDefault();
          if (objectApiName) {
            router.push(`/setup/approvals/${encodeURIComponent(objectApiName)}/new`);
          }
        }}
      >
        <label htmlFor="new-approval-object">Object</label>
        <select id="new-approval-object" value={objectApiName} onChange={(event) => setObjectApiName(event.target.value)}>
          <option value="">Choose an object</option>
          {objects.map((one) => (
            <option key={one.apiName} value={one.apiName}>
              {one.label}
            </option>
          ))}
        </select>
        <button type="submit" className="button" disabled={objectApiName === ""}>
          New approval process
        </button>
      </form>
      <ActionMessages notice={notice} problem={problem} testId="approval-processes" />
      {processes.length === 0 ? (
        <p>No approval processes yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Process</th>
              <th>Object</th>
              <th>Records</th>
              <th>Steps</th>
              <th>State</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {processes.map((process) => {
              const href = `/setup/approvals/${encodeURIComponent(process.objectApiName)}/${encodeURIComponent(process.apiName)}`;
              return (
                <tr key={`${process.objectApiName}.${process.apiName}`} data-testid="approval-process-row">
                  <td>
                    <Link className="table-row-link" href={href}>
                      {process.label}
                    </Link>
                    <div className="hint">
                      <code>{process.apiName}</code>
                    </div>
                  </td>
                  <td>{labelOfObject(process.objectApiName)}</td>
                  <td>{process.entryCriteria ? "Those that meet the criteria" : "Any record"}</td>
                  <td>{process.steps.map((step) => step.label).join(" → ")}</td>
                  <td>
                    <span className="badge">{process.active ? "On" : "Off"}</span>
                  </td>
                  <td>
                    <Link className="link-button" href={href}>
                      Build
                    </Link>{" "}
                    <Link
                      className="link-button"
                      href={`/approvals?object=${encodeURIComponent(process.objectApiName)}&process=${encodeURIComponent(process.apiName)}`}
                    >
                      Requests
                    </Link>{" "}
                    <button
                      type="button"
                      className="link-button"
                      disabled={busy}
                      onClick={() => switchTo(process, !process.active)}
                    >
                      {process.active ? "Switch off" : "Switch on"}
                    </button>{" "}
                    <button type="button" className="link-button" disabled={busy} onClick={() => remove(process)}>
                      Remove
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}
