"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { BuilderShell } from "@/components/builder/BuilderShell";
import { DropZone } from "@/components/builder/DropZone";
import { Palette } from "@/components/builder/Palette";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import type { DragPayload } from "@/lib/builder/dragPayload";
import {
  assignedObjectOf,
  definitionOf,
  draftFromView,
  emptyDraft,
  humanize,
  insertAction,
  moveActionBefore,
  newAction,
  objectsNamedBy,
  settableFields,
  snapshotOf,
  triggerHints,
  unfinishedActions,
  type ActionDraft,
  type Catalogue,
  type WorkflowDraft,
} from "@/lib/builder/workflowModel";
import { ExpressionCheck } from "./ExpressionCheck";
import { problemText } from "./objectText";
import { ACTION_GROUP, WorkflowActionCard } from "./WorkflowActionCard";
import { WorkflowTriggerEditor } from "./WorkflowTriggerEditor";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type WorkflowView = components["schemas"]["WorkflowView"];
type ObjectView = components["schemas"]["ObjectView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type FieldTypeView = components["schemas"]["FieldTypeView"];
type CheckView = components["schemas"]["WorkflowConditionCheckView"];

/**
 * The workflow builder (Sprint 23, ADR-0132, ADR-0138): when a workflow starts, the condition, and the actions in order,
 * built by dragging the action types the API offers into one list (or adding them with the keyboard). The page holds no list of
 * action types, trigger kinds, settings or limits: all of it is the catalogue the API gives, and the fields offered are the
 * ones the API says the object has and the platform does not work out. What it shows about a draft is a hint; whether the
 * workflow is acceptable is the API's answer, shown in the API's words with the place that is wrong. A draft is interface
 * state; the change set the person chose is the draft mechanism (Sprint 11).
 */
export function WorkflowBuilder() {
  const params = useParams<{ objectApiName: string; workflowApiName: string }>();
  const objectApiName = params.objectApiName;
  const workflowApiName = params.workflowApiName;
  const creating = workflowApiName === "new";
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();
  const [catalogue, setCatalogue] = useState<Catalogue | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [fieldTypes, setFieldTypes] = useState<FieldTypeView[]>([]);
  const [views, setViews] = useState<Record<string, ObjectView>>({});
  const [workflow, setWorkflow] = useState<WorkflowView | undefined>();
  const [draft, setDraft] = useState<WorkflowDraft | undefined>();
  const [baseline, setBaseline] = useState("");
  const [failure, setFailure] = useState<string | undefined>();
  const [checked, setChecked] = useState<CheckView | undefined>();
  const [checkFailure, setCheckFailure] = useState<string | undefined>();
  const [checking, setChecking] = useState(false);
  const asked = useRef(new Set<string>());

  const ensureObject = useCallback(async (name: string) => {
    if (name === "" || asked.current.has(name)) {
      return;
    }
    asked.current.add(name);
    try {
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}", {
        params: { path: { objectApiName: name } },
      });
      if (data) {
        setViews((known) => ({ ...known, [name]: data.data }));
      }
    } catch {
      // The action then offers no fields; naming the object again tries once more.
      asked.current.delete(name);
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const [catalogueAnswer, objectList, typeList] = await Promise.all([
        api.GET("/api/v1/metadata/workflows/action-types"),
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/metadata/field-types"),
      ]);
      if (!catalogueAnswer.data) {
        setFailure(await failureText(catalogueAnswer.error, catalogueAnswer.response));
        return;
      }
      setCatalogue(catalogueAnswer.data.data);
      setObjects(objectList.data?.data ?? []);
      setFieldTypes(typeList.data?.data ?? []);
      void ensureObject(objectApiName);
      if (creating) {
        const fresh = emptyDraft(catalogueAnswer.data.data);
        setDraft(fresh);
        setBaseline(snapshotOf(fresh));
        return;
      }
      const found = await api.GET("/api/v1/metadata/workflows/{objectApiName}/{workflowApiName}", {
        params: { path: { objectApiName, workflowApiName } },
      });
      if (!found.data) {
        setFailure(await failureText(found.error, found.response));
        return;
      }
      const read = draftFromView(found.data.data);
      setWorkflow(found.data.data);
      setDraft(read);
      setBaseline(snapshotOf(read));
      setFailure(undefined);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName, workflowApiName, creating, ensureObject]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const reload = useCallback(async () => {
    if (!creating) {
      await load();
    }
  }, [creating, load]);
  const { busy, notice, problem, act } = useAction(reload);

  // The objects the actions refer to are read once each, so their fields can be offered.
  const named = useMemo(() => {
    if (!draft || !catalogue) {
      return [];
    }
    return draft.actions.flatMap((action) => {
      const type = catalogue.actionTypes.find((one) => one.type === action.type);
      return [...objectsNamedBy(action, type), assignedObjectOf(action, type, objectApiName) ?? ""];
    });
  }, [draft, catalogue, objectApiName]);
  useEffect(() => {
    named.forEach((name) => void ensureObject(name));
  }, [named, ensureObject]);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="workflow-builder-failed">
        {failure}
      </p>
    );
  }
  if (!catalogue || !draft) {
    return <p aria-live="polite">Loading…</p>;
  }

  const current: WorkflowDraft = draft;
  const kinds = catalogue;
  const dirty = baseline !== "" && snapshotOf(current) !== baseline;
  const listHref = "/setup/workflows";
  const open = unfinishedActions(current, kinds);
  const kind = kinds.triggerKinds.find((one) => one.kind === current.trigger.kind);
  const triggerOpen = triggerHints(current.trigger, kind);
  const fieldsOf = (name: string) => views[name]?.fields ?? [];
  const settableOf = (name: string) => settableFields(fieldsOf(name), fieldTypes);
  const workflowFields = fieldsOf(objectApiName);

  function edit(change: Partial<WorkflowDraft>) {
    setChecked(undefined);
    setDraft((now) => (now ? { ...now, ...change } : now));
  }

  function editActions(change: (actions: readonly ActionDraft[]) => ActionDraft[]) {
    setChecked(undefined);
    setDraft((now) => (now ? { ...now, actions: change(now.actions) } : now));
  }

  function add(typeCode: string, before?: number) {
    editActions((actions) => insertAction(actions, newAction(typeCode, actions), before));
  }

  function dropAt(payload: DragPayload, before?: number) {
    if (payload.origin === "palette") {
      add(payload.key.slice("action:".length), before);
    } else if (payload.group === ACTION_GROUP) {
      editActions((actions) => moveActionBefore(actions, payload.index, before));
    }
  }

  async function tryActions() {
    setChecking(true);
    setCheckFailure(undefined);
    try {
      const definition = definitionOf(current, kinds);
      const { data, error, response } = await api.POST("/api/v1/metadata/workflows/{objectApiName}/check", {
        params: { path: { objectApiName } },
        body: { condition: definition.condition, actions: definition.actions },
      });
      if (data) {
        setChecked(data.data);
      } else {
        setChecked(undefined);
        setCheckFailure(problemText(error, response));
      }
    } catch {
      setCheckFailure(COMMON_TEXT.network);
    }
    setChecking(false);
  }

  function save() {
    const definition = definitionOf(current, kinds);
    void act(async () => {
      if (!creating && workflow) {
        const update = {
          label: current.label,
          description: current.description,
          active: current.active,
          ...definition,
          version: workflow.version,
        };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_WORKFLOW",
              objectApiName,
              itemApiName: workflow.apiName,
              updateWorkflow: update,
            })
          : await api.PUT("/api/v1/metadata/workflows/{objectApiName}/{workflowApiName}", {
              params: { path: { objectApiName, workflowApiName: workflow.apiName } },
              body: update,
            });
        if (result.response.ok && workingSet) {
          setBaseline(snapshotOf(current));
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The workflow was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = {
        name: current.name,
        label: current.label,
        description: current.description,
        active: current.active,
        ...definition,
      };
      if (workingSet) {
        const drafted = await addToSet(workingSet, { kind: "CREATE_WORKFLOW", objectApiName, createWorkflow: request });
        if (drafted.response.ok) {
          setBaseline(snapshotOf(current));
          router.replace(listHref);
        }
        return {
          error: drafted.error,
          response: drafted.response,
          text: DRAFTED_TEXT,
          problem: problemText(drafted.error, drafted.response),
        };
      }
      const result = await api.POST("/api/v1/metadata/workflows/{objectApiName}", {
        params: { path: { objectApiName } },
        body: request,
      });
      if (result.response.ok && result.data) {
        setBaseline(snapshotOf(current));
        router.replace(`${listHref}/${encodeURIComponent(objectApiName)}/${encodeURIComponent(result.data.data.apiName)}`);
      }
      return {
        error: result.error,
        response: result.response,
        text: "The workflow was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const problemsOf = (index: number): string[] =>
    Object.entries(checked?.actionProblems ?? {})
      .filter(([place]) => place === `actions[${index}]` || place.startsWith(`actions[${index}].`))
      .flatMap(([place, texts]) => texts.map((text) => `${place.slice(`actions[${index}]`.length + 1) || "Action"}: ${text}`));
  const generalProblems = (checked?.actionProblems?.["actions"] ?? []).map((text) => text);
  const title = creating ? "New workflow" : current.label || workflowApiName;

  return (
    <BuilderShell
      testId="workflow-builder"
      crumbs={[{ label: "Workflows", href: listHref }, { label: title }]}
      title={title}
      backHref={listHref}
      dirty={dirty}
      busy={busy}
      saveLabel={creating ? "Add the workflow" : "Save the workflow"}
      onSave={save}
      actions={
        <button type="button" className="button button-secondary" disabled={checking} onClick={() => void tryActions()}>
          Check the actions
        </button>
      }
    >
      <ActionMessages notice={notice} problem={problem} testId="workflow-builder" />
      <section aria-labelledby="workflow-identity-heading" className="card">
        <h3 id="workflow-identity-heading">About this workflow</h3>
        <p className="hint">
          A workflow of the object <code>{objectApiName}</code>.{" "}
          {!creating ? (
            <Link href={`/workflows/executions?workflow=${encodeURIComponent(workflowApiName)}`}>See its runs</Link>
          ) : null}
        </p>
        <div className="record-grid record-grid-two">
          {creating ? (
            <div className="field">
              <label htmlFor="workflow-name">Name</label>
              <input
                id="workflow-name"
                value={current.name}
                maxLength={40}
                aria-describedby="workflow-name-hint"
                onChange={(event) => edit({ name: event.target.value })}
              />
              <p className="hint" id="workflow-name-hint">
                The permanent name, starting with a capital letter, for example ApproveLargeOrders. The platform adds the
                ending __c.
              </p>
            </div>
          ) : (
            <p>
              <code>{workflow?.apiName}</code>. The name of a workflow never changes.
            </p>
          )}
          <div className="field">
            <label htmlFor="workflow-label">Label</label>
            <input
              id="workflow-label"
              value={current.label}
              maxLength={80}
              onChange={(event) => edit({ label: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="workflow-description">Description</label>
            <input
              id="workflow-description"
              value={current.description}
              maxLength={500}
              onChange={(event) => edit({ description: event.target.value })}
            />
          </div>
          <label>
            <input type="checkbox" checked={current.active} onChange={(event) => edit({ active: event.target.checked })} />{" "}
            Switched on (it starts runs)
          </label>
        </div>
      </section>

      <section aria-labelledby="workflow-when-heading" className="card">
        <h3 id="workflow-when-heading">When</h3>
        <WorkflowTriggerEditor
          catalogue={kinds}
          objectApiName={objectApiName}
          trigger={current.trigger}
          fields={workflowFields}
          onChange={(trigger) => edit({ trigger })}
        />
        {triggerOpen.length > 0 ? (
          <ul className="hint" data-testid="trigger-hints">
            {triggerOpen.map((hint) => (
              <li key={hint}>{hint}</li>
            ))}
          </ul>
        ) : null}
      </section>

      <section aria-labelledby="workflow-if-heading" className="card">
        <h3 id="workflow-if-heading">If (optional)</h3>
        <div className="field">
          <label htmlFor="workflow-condition">Condition</label>
          <textarea
            id="workflow-condition"
            rows={3}
            maxLength={4000}
            value={current.condition}
            aria-describedby="workflow-condition-hint"
            onChange={(event) => edit({ condition: event.target.value })}
          />
          <p className="hint" id="workflow-condition-hint">
            Left empty, the workflow always runs. It is judged on the record as it is when the run starts.
          </p>
        </div>
        <ExpressionCheck
          objectApiName={objectApiName}
          expression={current.condition}
          purpose="WORKFLOW_CONDITION"
          fields={workflowFields}
          testId="workflow-condition-check"
        />
      </section>

      <section aria-labelledby="workflow-then-heading" className="stack">
        <h3 id="workflow-then-heading">Then</h3>
        <p className="hint">
          The actions, in order. Drag an action type in, or choose it in the palette. If an action fails for a reason that a
          later attempt may mend, it is tried again; otherwise the run waits in the list of runs for a person to replay it.
        </p>
        <div className="builder">
          <Palette
            label="Actions"
            categories={[
              {
                id: "actions",
                label: "Actions",
                empty: "This release offers no action.",
                items: kinds.actionTypes.map((one) => ({
                  key: `action:${one.type}`,
                  label: humanize(one.type),
                  title: one.summary,
                })),
              },
            ]}
            onPick={(item) => add(item.key.slice("action:".length))}
          />
          <DropZone
            className="canvas-card"
            aria-label="Actions in order"
            data-testid="actions-canvas"
            onDropPayload={(payload: DragPayload) => dropAt(payload)}
          >
            <div className="canvas-card-head">
              <strong>Actions, in order</strong>
              <span className="hint">
                {current.actions.length} {current.actions.length === 1 ? "action" : "actions"}
              </span>
            </div>
            <div className="canvas-items is-list">
              {current.actions.length === 0 ? (
                <p className="canvas-empty">Nothing yet. Drag an action here, or choose one in the palette.</p>
              ) : null}
              {current.actions.map((action, index) => (
                <WorkflowActionCard
                  key={action.id}
                  index={index}
                  count={current.actions.length}
                  action={action}
                  type={kinds.actionTypes.find((one) => one.type === action.type)}
                  workflowObject={objectApiName}
                  objects={objects}
                  fieldsOf={fieldsOf}
                  settableOf={settableOf}
                  problems={problemsOf(index)}
                  attemptsLimit={kinds.limits.maxAttempts}
                  onChange={editActions}
                  onDrop={dropAt}
                />
              ))}
            </div>
            {open.length > 0 ? (
              <p className="hint" data-testid="actions-unfinished">
                {open.length} {open.length === 1 ? "action is" : "actions are"} not finished yet.
              </p>
            ) : null}
          </DropZone>
        </div>
        <p className="hint">
          At most {kinds.limits.maxActionsPerWorkflow} actions in a workflow, {kinds.limits.maxAssignmentsPerAction} fields
          in an action, {kinds.limits.maxLiteralLength} characters in a value. A change started by a workflow can start
          another workflow, up to {kinds.limits.maxChainDepth} in a row.
        </p>
      </section>

      <section aria-labelledby="workflow-check-heading" className="stack" role="status" aria-live="polite">
        <h3 id="workflow-check-heading" className="visually-hidden">
          Result of checking the actions
        </h3>
        {checkFailure ? (
          <p className="form-message" role="alert" data-testid="workflow-check-failed">
            {checkFailure}
          </p>
        ) : null}
        {checked && Object.keys(checked.actionProblems).length === 0 ? (
          <p data-testid="workflow-check-ok">The actions fit the object. Nothing was saved.</p>
        ) : null}
        {generalProblems.length > 0 ? (
          <ul className="form-message" role="alert" data-testid="workflow-check-general">
            {generalProblems.map((text) => (
              <li key={text}>{text}</li>
            ))}
          </ul>
        ) : null}
      </section>
    </BuilderShell>
  );
}
