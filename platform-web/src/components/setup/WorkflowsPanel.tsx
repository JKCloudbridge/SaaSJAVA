"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { triggerText, type Catalogue } from "@/lib/builder/workflowModel";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type WorkflowView = components["schemas"]["WorkflowView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

/**
 * The workflows of the organization: the list, with the way into the builder where a workflow is made and changed and the
 * way to the runs of each. Switching on or off and removing are done here, live or, when a change set is chosen, as a draft.
 * The page shows what the API answers and in its words; a workflow that cannot be switched on says why.
 */
export function WorkflowsPanel() {
  const { id: workingSet } = useWorkingSet();
  const router = useRouter();
  const [workflows, setWorkflows] = useState<WorkflowView[] | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [catalogue, setCatalogue] = useState<Catalogue | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [objectApiName, setObjectApiName] = useState("");

  const reload = useCallback(async () => {
    try {
      const [list, objectList, kinds] = await Promise.all([
        api.GET("/api/v1/metadata/workflows"),
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/metadata/workflows/action-types"),
      ]);
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setWorkflows(list.data.data);
      setObjects(objectList.data?.data ?? []);
      setCatalogue(kinds.data?.data);
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
      <p className="form-message" role="alert" data-testid="workflows-failed">
        {failure}
      </p>
    );
  }
  if (!workflows) {
    return <p aria-live="polite">Loading…</p>;
  }

  function switchTo(workflow: WorkflowView, active: boolean) {
    void act(async () => {
      if (workingSet) {
        const result = await addToSet(workingSet, {
          kind: "UPDATE_WORKFLOW",
          objectApiName: workflow.objectApiName,
          itemApiName: workflow.apiName,
          updateWorkflow: {
            label: workflow.label,
            description: workflow.description,
            active,
            trigger: workflow.trigger,
            condition: workflow.condition,
            actions: workflow.actions,
            version: workflow.version,
          },
        });
        return {
          error: result.error,
          response: result.response,
          text: DRAFTED_TEXT,
          problem: problemText(result.error, result.response),
        };
      }
      const result = await api.POST("/api/v1/metadata/workflows/{objectApiName}/{workflowApiName}/activation", {
        params: { path: { objectApiName: workflow.objectApiName, workflowApiName: workflow.apiName } },
        body: { active, version: workflow.version },
      });
      return {
        error: result.error,
        response: result.response,
        text: active ? "The workflow is switched on." : "The workflow is switched off.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(workflow: WorkflowView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "DELETE_WORKFLOW",
            objectApiName: workflow.objectApiName,
            itemApiName: workflow.apiName,
          })
        : await api.DELETE("/api/v1/metadata/workflows/{objectApiName}/{workflowApiName}", {
            params: { path: { objectApiName: workflow.objectApiName, workflowApiName: workflow.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The workflow was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const labelOfObject = (name: string) => objects.find((one) => one.apiName === name)?.label ?? name;

  return (
    <section aria-labelledby="workflows-heading" data-testid="workflows">
      <div className="list-head">
        <h2 id="workflows-heading">Workflows</h2>
        <Link className="button button-secondary" href="/workflows/executions">
          Workflow runs
        </Link>
      </div>
      <p className="hint">
        A workflow reacts to something that happens to a record, or to a time: if its condition holds, it runs its actions
        in order, after the change is saved. A run that cannot finish is kept in the list of runs for a person to replay.
      </p>
      <form
        className="filters"
        aria-label="Start a new workflow"
        onSubmit={(event) => {
          event.preventDefault();
          if (objectApiName) {
            router.push(`/setup/workflows/${encodeURIComponent(objectApiName)}/new`);
          }
        }}
      >
        <label htmlFor="new-workflow-object">Object</label>
        <select
          id="new-workflow-object"
          value={objectApiName}
          onChange={(event) => setObjectApiName(event.target.value)}
        >
          <option value="">Choose an object</option>
          {objects.map((one) => (
            <option key={one.apiName} value={one.apiName}>
              {one.label}
            </option>
          ))}
        </select>
        <button type="submit" className="button" disabled={objectApiName === ""}>
          New workflow
        </button>
      </form>
      <ActionMessages notice={notice} problem={problem} testId="workflows" />
      {workflows.length === 0 ? (
        <p>No workflows yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Workflow</th>
              <th>Object</th>
              <th>Starts when</th>
              <th>Actions</th>
              <th>State</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {workflows.map((workflow) => {
              const href = `/setup/workflows/${encodeURIComponent(workflow.objectApiName)}/${encodeURIComponent(workflow.apiName)}`;
              return (
                <tr key={`${workflow.objectApiName}.${workflow.apiName}`} data-testid="workflow-row">
                  <td>
                    <Link className="table-row-link" href={href}>
                      {workflow.label}
                    </Link>
                    <div className="hint">
                      <code>{workflow.apiName}</code>
                    </div>
                  </td>
                  <td>{labelOfObject(workflow.objectApiName)}</td>
                  <td>{catalogue ? triggerText(workflow.trigger, catalogue) : workflow.trigger.kind}</td>
                  <td>{workflow.actions.length}</td>
                  <td>
                    <span className="badge">{workflow.active ? "On" : "Off"}</span>
                  </td>
                  <td>
                    <Link className="link-button" href={href}>
                      Build
                    </Link>{" "}
                    <Link
                      className="link-button"
                      href={`/workflows/executions?workflow=${encodeURIComponent(workflow.apiName)}`}
                    >
                      Runs
                    </Link>{" "}
                    <button
                      type="button"
                      className="link-button"
                      disabled={busy}
                      onClick={() => switchTo(workflow, !workflow.active)}
                    >
                      {workflow.active ? "Switch off" : "Switch on"}
                    </button>{" "}
                    <button type="button" className="link-button" disabled={busy} onClick={() => remove(workflow)}>
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
