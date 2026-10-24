"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { triggerText, type Catalogue } from "@/lib/builder/workflowModel";
import { useOffers } from "@/lib/session/SessionProvider";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type WorkflowView = components["schemas"]["WorkflowView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

const hrefOf = (workflow: WorkflowView) =>
  `/setup/workflows/${encodeURIComponent(workflow.objectApiName)}/${encodeURIComponent(workflow.apiName)}`;

/**
 * The workflows of the organization: the list, with the way into the builder where a workflow is made and changed (a row
 * opens it) and the way to the runs of each. Switching on or off and removing are done here, live or, when a change set is
 * chosen, as a draft. A new workflow starts on the object chosen in the title band. The page shows what the API answers and
 * in its words; a workflow that cannot be switched on says why.
 */
export function WorkflowsPanel() {
  const { id: workingSet } = useWorkingSet();
  const router = useRouter();
  const [workflows, setWorkflows] = useState<WorkflowView[] | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [catalogue, setCatalogue] = useState<Catalogue | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [objectApiName, setObjectApiName] = useState("");
  const offersManage = useOffers("workflows.manage");

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

  const tools = (
    <>
      <Link className="button button-secondary" href="/workflows/executions">
        Workflow runs
      </Link>
      {offersManage ? (
        <form
          className="form-actions"
          aria-label="Start a new workflow"
          onSubmit={(event) => {
            event.preventDefault();
            if (objectApiName) {
              router.push(`/setup/workflows/${encodeURIComponent(objectApiName)}/new`);
            }
          }}
        >
          <label className="visually-hidden" htmlFor="new-workflow-object">
            Object
          </label>
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
      ) : null}
    </>
  );

  return (
    <ListPage<WorkflowView>
      title="Workflows"
      items={workflows}
      failure={failure}
      rowKey={(row) => `${row.objectApiName}.${row.apiName}`}
      primaryHeader="Workflow"
      primary={(row) => row.label}
      primaryExtra={(row) => <code>{row.apiName}</code>}
      openHref={hrefOf}
      columns={[
        { key: "object", header: "Object", kind: "muted", cell: (row) => labelOfObject(row.objectApiName) },
        {
          key: "trigger",
          header: "Starts when",
          kind: "muted",
          cell: (row) => (catalogue ? triggerText(row.trigger, catalogue) : row.trigger.kind),
        },
        { key: "actions-count", header: "Actions", kind: "muted", cell: (row) => row.actions.length },
        {
          key: "state",
          header: "Status",
          kind: "tag",
          cell: (row) => <Tag tone={row.active ? "ok" : "quiet"}>{row.active ? "On" : "Off"}</Tag>,
        },
        {
          key: "row-actions",
          header: "Row actions",
          kind: "actions",
          cell: (row) => (
            <>
              <Link
                className="link-button"
                href={`/workflows/executions?workflow=${encodeURIComponent(row.apiName)}`}
              >
                Runs
              </Link>{" "}
              <button type="button" className="link-button" disabled={busy} onClick={() => switchTo(row, !row.active)}>
                {row.active ? "Switch off" : "Switch on"}
              </button>{" "}
              <button type="button" className="link-button" disabled={busy} onClick={() => remove(row)}>
                Remove
              </button>
            </>
          ),
        },
      ]}
      searchText={(row) => `${row.label} ${row.apiName} ${labelOfObject(row.objectApiName)}`}
      tools={tools}
      notices={<ActionMessages notice={notice} problem={problem} testId="workflows" />}
      emptyText="No workflows yet."
      testId="workflows"
      rowTestId="workflow-row"
    />
  );
}
