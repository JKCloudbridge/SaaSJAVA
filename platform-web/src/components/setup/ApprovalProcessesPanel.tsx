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
import { useOffers } from "@/lib/session/SessionProvider";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ProcessView = components["schemas"]["ApprovalProcessView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

const hrefOf = (process: ProcessView) =>
  `/setup/approvals/${encodeURIComponent(process.objectApiName)}/${encodeURIComponent(process.apiName)}`;

/**
 * The approval processes of the organization: the list, with the way into the builder where a process is made and changed
 * (a row opens it) and the way to the requests under each. Switching on or off and removing are done here, live or, when a
 * change set is chosen, as a draft. A new process starts on the object chosen in the title band. The page shows what the API
 * answers and in its words; a process that cannot be switched on or removed says why (the plan has no approvals, requests
 * are waiting for a decision).
 */
export function ApprovalProcessesPanel() {
  const { id: workingSet } = useWorkingSet();
  const router = useRouter();
  const [processes, setProcesses] = useState<ProcessView[] | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [objectApiName, setObjectApiName] = useState("");
  const offersManage = useOffers("approvals.manage");

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

  const tools = (
    <>
      <Link className="button button-secondary" href="/approvals">
        Approvals inbox
      </Link>
      {offersManage ? (
        <form
          className="form-actions"
          aria-label="Start a new approval process"
          onSubmit={(event) => {
            event.preventDefault();
            if (objectApiName) {
              router.push(`/setup/approvals/${encodeURIComponent(objectApiName)}/new`);
            }
          }}
        >
          <label className="visually-hidden" htmlFor="new-approval-object">
            Object
          </label>
          <select
            id="new-approval-object"
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
            New approval process
          </button>
        </form>
      ) : null}
    </>
  );

  return (
    <ListPage<ProcessView>
      title="Approval processes"
      items={processes}
      failure={failure}
      rowKey={(row) => `${row.objectApiName}.${row.apiName}`}
      primaryHeader="Process"
      primary={(row) => row.label}
      primaryExtra={(row) => <code>{row.apiName}</code>}
      openHref={hrefOf}
      columns={[
        { key: "object", header: "Object", kind: "muted", cell: (row) => labelOfObject(row.objectApiName) },
        {
          key: "criteria",
          header: "Entry criteria",
          kind: "muted",
          cell: (row) => (row.entryCriteria ? "Those that meet the criteria" : "Any record"),
        },
        { key: "steps", header: "Steps", kind: "muted", cell: (row) => row.steps.map((step) => step.label).join(" → ") },
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
                href={`/approvals?object=${encodeURIComponent(row.objectApiName)}&process=${encodeURIComponent(row.apiName)}`}
              >
                Requests
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
      notices={<ActionMessages notice={notice} problem={problem} testId="approval-processes" />}
      emptyText="No approval processes yet."
      testId="approvals"
      rowTestId="approval-process-row"
    />
  );
}
