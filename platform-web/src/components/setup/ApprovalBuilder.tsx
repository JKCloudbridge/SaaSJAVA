"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { PersonPicker } from "@/components/approvals/PersonPicker";
import { COMMON_TEXT } from "@/components/account/messages";
import { BuilderShell } from "@/components/builder/BuilderShell";
import { DropZone } from "@/components/builder/DropZone";
import { Palette } from "@/components/builder/Palette";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import {
  addOutcomeField,
  changeOutcomeField,
  definitionOf,
  draftFromView,
  emptyDraft,
  humanize,
  insertStep,
  moveStepBefore,
  newStep,
  removeOutcomeField,
  settableFields,
  snapshotOf,
  unfinishedOutcomes,
  unfinishedSteps,
  userFields,
  addApprover,
  type ApprovalDraft,
  type Catalogue,
  type StepDraft,
} from "@/lib/builder/approvalModel";
import type { DragPayload } from "@/lib/builder/dragPayload";
import { ExpressionCheck } from "./ExpressionCheck";
import { problemText } from "./objectText";
import { ApprovalStepCard, STEP_GROUP } from "./ApprovalStepCard";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ProcessView = components["schemas"]["ApprovalProcessView"];
type ObjectView = components["schemas"]["ObjectView"];
type FieldTypeView = components["schemas"]["FieldTypeView"];
type Choices = components["schemas"]["ApprovalChoicesView"];
type CheckView = components["schemas"]["ApprovalProcessCheckView"];
type ResolutionView = components["schemas"]["ApprovalResolutionView"];

/** The key of the one chip that makes a step; the chips of the rules are keyed `rule:` and the kind the API gave. */
const STEP_CHIP = "step:new";
const RULE_PREFIX = "rule:";

/**
 * The approval builder (Sprint 24, ADR-0132, ADR-0145): when a record may be submitted (the entry criteria), the steps in
 * order with the rules that say who is asked and how many must approve, what the end of a request sets on the record, and
 * the fallback approver. The page holds no list of kinds of approver, completion rules, outcomes, states or limits: all of
 * it is the catalogue the API gives, and the fields offered are the ones the API says the object has. What it shows about a
 * draft is a hint; whether the process is acceptable is the API's answer, in its words with the place that is wrong. Who a
 * step would ask is never worked out here: the API resolves it for a record the person names, and nothing is stored. A draft
 * is interface state; the change set the person chose is the draft mechanism (Sprint 11).
 */
export function ApprovalBuilder() {
  const params = useParams<{ objectApiName: string; processApiName: string }>();
  const objectApiName = params.objectApiName;
  const processApiName = params.processApiName;
  const creating = processApiName === "new";
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();
  const [catalogue, setCatalogue] = useState<Catalogue | undefined>();
  const [choices, setChoices] = useState<Choices | undefined>();
  const [object, setObject] = useState<ObjectView | undefined>();
  const [fieldTypes, setFieldTypes] = useState<FieldTypeView[]>([]);
  const [process, setProcess] = useState<ProcessView | undefined>();
  const [draft, setDraft] = useState<ApprovalDraft | undefined>();
  const [baseline, setBaseline] = useState("");
  const [failure, setFailure] = useState<string | undefined>();
  const [names, setNames] = useState<Record<string, string>>({});
  const [checked, setChecked] = useState<CheckView | undefined>();
  const [checkFailure, setCheckFailure] = useState<string | undefined>();
  const [checking, setChecking] = useState(false);
  const [recordId, setRecordId] = useState("");
  const [resolution, setResolution] = useState<ResolutionView | undefined>();
  const [resolveFailure, setResolveFailure] = useState<string | undefined>();

  const load = useCallback(async () => {
    try {
      const [catalogueAnswer, choiceAnswer, objectAnswer, typeList] = await Promise.all([
        api.GET("/api/v1/metadata/approvals/catalogue"),
        api.GET("/api/v1/metadata/approvals/choices"),
        api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } } }),
        api.GET("/api/v1/metadata/field-types"),
      ]);
      if (!catalogueAnswer.data) {
        setFailure(await failureText(catalogueAnswer.error, catalogueAnswer.response));
        return;
      }
      setChoices(choiceAnswer.data?.data);
      setObject(objectAnswer.data?.data);
      setFieldTypes(typeList.data?.data ?? []);
      setCatalogue(catalogueAnswer.data.data);
      if (creating) {
        const fresh = emptyDraft(catalogueAnswer.data.data);
        setDraft(fresh);
        setBaseline(snapshotOf(fresh));
        return;
      }
      const found = await api.GET("/api/v1/metadata/approvals/{objectApiName}/{processApiName}", {
        params: { path: { objectApiName, processApiName } },
      });
      if (!found.data) {
        setFailure(await failureText(found.error, found.response));
        return;
      }
      const read = draftFromView(found.data.data, catalogueAnswer.data.data);
      setProcess(found.data.data);
      setDraft(read);
      setBaseline(snapshotOf(read));
      setFailure(undefined);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName, processApiName, creating]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const reload = useCallback(async () => {
    if (!creating) {
      await load();
    }
  }, [creating, load]);
  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="approval-builder-failed">
        {failure}
      </p>
    );
  }
  if (!catalogue || !draft) {
    return <p aria-live="polite">Loading…</p>;
  }

  const current: ApprovalDraft = draft;
  const kinds = catalogue;
  const dirty = baseline !== "" && snapshotOf(current) !== baseline;
  const listHref = "/setup/approvals";
  const open = unfinishedSteps(current, kinds);
  const fields = object?.fields ?? [];
  const calculated = new Set(fieldTypes.filter((type) => type.calculated).map((type) => type.type));
  const settable = settableFields(fields, calculated);
  const peopleFields = userFields(fields);
  const fallbackKinds = kinds.approverKinds.filter(
    (one) => one.valueRefersTo === "USER" || one.valueRefersTo === "GROUP",
  );

  function edit(change: Partial<ApprovalDraft>) {
    setChecked(undefined);
    setDraft((now) => (now ? { ...now, ...change } : now));
  }

  function editSteps(change: (steps: readonly StepDraft[]) => StepDraft[]) {
    setChecked(undefined);
    setResolution(undefined);
    setDraft((now) => (now ? { ...now, steps: change(now.steps) } : now));
  }

  function pick(key: string, before?: number) {
    if (key === STEP_CHIP) {
      editSteps((steps) => insertStep(steps, newStep(steps, kinds), before));
    } else if (key.startsWith(RULE_PREFIX)) {
      const kind = key.slice(RULE_PREFIX.length);
      editSteps((steps) => {
        const withStep = steps.length === 0 ? insertStep(steps, newStep(steps, kinds)) : [...steps];
        const target = before !== undefined && before < withStep.length ? before : withStep.length - 1;
        return addApprover(withStep, target, kind);
      });
    }
  }

  function dropAt(payload: DragPayload, before?: number) {
    if (payload.origin === "palette") {
      pick(payload.key, before);
    } else if (payload.group === STEP_GROUP) {
      editSteps((steps) => moveStepBefore(steps, payload.index, before));
    }
  }

  async function tryDefinition() {
    setChecking(true);
    setCheckFailure(undefined);
    try {
      const definition = definitionOf(current, kinds);
      const { data, error, response } = await api.POST("/api/v1/metadata/approvals/{objectApiName}/check", {
        params: { path: { objectApiName } },
        body: { entryCriteria: definition.entryCriteria, steps: definition.steps, outcomes: definition.outcomes },
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

  async function whoWouldBeAsked() {
    setResolveFailure(undefined);
    try {
      const definition = definitionOf(current, kinds);
      const { data, error, response } = await api.POST("/api/v1/metadata/approvals/{objectApiName}/resolve", {
        params: { path: { objectApiName } },
        body: {
          recordId: recordId.trim(),
          steps: definition.steps,
          fallback: definition.fallback,
          allowSelfApproval: definition.allowSelfApproval,
        },
      });
      if (data) {
        setResolution(data.data);
      } else {
        setResolution(undefined);
        setResolveFailure(problemText(error, response));
      }
    } catch {
      setResolveFailure(COMMON_TEXT.network);
    }
  }

  function save() {
    const definition = definitionOf(current, kinds);
    void act(async () => {
      if (!creating && process) {
        const update = {
          label: current.label,
          description: current.description,
          active: current.active,
          ...definition,
          version: process.version,
        };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_APPROVAL_PROCESS",
              objectApiName,
              itemApiName: process.apiName,
              updateApprovalProcess: update,
            })
          : await api.PUT("/api/v1/metadata/approvals/{objectApiName}/{processApiName}", {
              params: { path: { objectApiName, processApiName: process.apiName } },
              body: update,
            });
        if (result.response.ok && workingSet) {
          setBaseline(snapshotOf(current));
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The approval process was saved.",
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
        const drafted = await addToSet(workingSet, {
          kind: "CREATE_APPROVAL_PROCESS",
          objectApiName,
          createApprovalProcess: request,
        });
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
      const result = await api.POST("/api/v1/metadata/approvals/{objectApiName}", {
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
        text: "The approval process was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const problemsOf = (index: number): string[] =>
    Object.entries(checked?.problems ?? {})
      .filter(([place]) => place === `steps[${index}]` || place.startsWith(`steps[${index}].`))
      .flatMap(([place, texts]) => texts.map((text) => `${place.slice(`steps[${index}]`.length + 1) || "Step"}: ${text}`));
  const outcomeProblems = Object.entries(checked?.problems ?? {})
    .filter(([place]) => place.startsWith("outcomes"))
    .flatMap(([place, texts]) => texts.map((text) => `${place}: ${text}`));
  const title = creating ? "New approval process" : current.label || processApiName;

  return (
    <BuilderShell
      testId="approval-builder"
      crumbs={[{ label: "Approval processes", href: listHref }, { label: title }]}
      title={title}
      backHref={listHref}
      dirty={dirty}
      busy={busy}
      saveLabel={creating ? "Add the process" : "Save the process"}
      onSave={save}
      actions={
        <button type="button" className="button button-secondary" disabled={checking} onClick={() => void tryDefinition()}>
          Check the process
        </button>
      }
    >
      <ActionMessages notice={notice} problem={problem} testId="approval-builder" />

      <section aria-labelledby="approval-identity-heading" className="card">
        <h3 id="approval-identity-heading">About this process</h3>
        <p className="hint">
          An approval process of the object <code>{objectApiName}</code>. A request that is already under way keeps the
          version of the process it started under, whatever is saved here.
        </p>
        <div className="record-grid record-grid-two">
          {creating ? (
            <div className="field">
              <label htmlFor="approval-name">Name</label>
              <input
                id="approval-name"
                value={current.name}
                maxLength={40}
                aria-describedby="approval-name-hint"
                onChange={(event) => edit({ name: event.target.value })}
              />
              <p className="hint" id="approval-name-hint">
                The permanent name, starting with a capital letter, for example LargeExpense. The platform adds the ending __c.
              </p>
            </div>
          ) : (
            <p>
              <code>{process?.apiName}</code>. The name of a process never changes.
            </p>
          )}
          <div className="field">
            <label htmlFor="approval-label">Label</label>
            <input
              id="approval-label"
              value={current.label}
              maxLength={80}
              onChange={(event) => edit({ label: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="approval-description">Description</label>
            <input
              id="approval-description"
              value={current.description}
              maxLength={500}
              onChange={(event) => edit({ description: event.target.value })}
            />
          </div>
          <label>
            <input type="checkbox" checked={current.active} onChange={(event) => edit({ active: event.target.checked })} />{" "}
            Switched on (new requests may start under it)
          </label>
          <label>
            <input
              type="checkbox"
              checked={current.allowSelfApproval}
              onChange={(event) => edit({ allowSelfApproval: event.target.checked })}
            />{" "}
            The person who submits may also be asked to decide
          </label>
        </div>
      </section>

      <section aria-labelledby="approval-criteria-heading" className="card">
        <h3 id="approval-criteria-heading">Which records (optional)</h3>
        <div className="field">
          <label htmlFor="approval-criteria">Entry criteria</label>
          <textarea
            id="approval-criteria"
            rows={3}
            maxLength={4000}
            value={current.entryCriteria}
            aria-describedby="approval-criteria-hint"
            onChange={(event) => edit({ entryCriteria: event.target.value })}
          />
          <p className="hint" id="approval-criteria-hint">
            Left empty, any record can be submitted. Otherwise a record can be submitted only while the criteria are true for
            it, judged on the record as it is when it is submitted.
          </p>
        </div>
        <ExpressionCheck
          objectApiName={objectApiName}
          expression={current.entryCriteria}
          purpose="APPROVAL_ENTRY"
          fields={fields}
          testId="approval-criteria-check"
        />
      </section>

      <section aria-labelledby="approval-steps-heading" className="stack">
        <h3 id="approval-steps-heading">Steps</h3>
        <p className="hint">
          The steps, in order. A record waits at a step until it is decided; one rejection ends the request. Drag a step in,
          or choose it in the palette, then say who is asked.
        </p>
        <div className="builder">
          <Palette
            label="Steps and rules"
            categories={[
              {
                id: "steps",
                label: "Steps",
                empty: "Nothing to add.",
                items: [{ key: STEP_CHIP, label: "A step", title: "Adds a step at the end, or where you drop it.", emphasis: true }],
              },
              {
                id: "rules",
                label: "Who is asked",
                empty: "This release offers no rule.",
                items: kinds.approverKinds.map((one) => ({
                  key: `${RULE_PREFIX}${one.kind}`,
                  label: humanize(one.kind),
                  title: one.summary,
                })),
              },
            ]}
            onPick={(item) => pick(item.key)}
          />
          <DropZone
            className="canvas-card"
            aria-label="Steps in order"
            data-testid="steps-canvas"
            onDropPayload={(payload: DragPayload) => dropAt(payload)}
          >
            <div className="canvas-card-head">
              <strong>Steps, in order</strong>
              <span className="hint">
                {current.steps.length} {current.steps.length === 1 ? "step" : "steps"}
              </span>
            </div>
            <div className="canvas-items is-list">
              {current.steps.length === 0 ? (
                <p className="canvas-empty">Nothing yet. Drag a step here, or choose one in the palette.</p>
              ) : null}
              {current.steps.map((step, index) => (
                <ApprovalStepCard
                  key={step.id}
                  index={index}
                  count={current.steps.length}
                  step={step}
                  catalogue={kinds}
                  choices={choices}
                  userFields={peopleFields}
                  names={names}
                  problems={problemsOf(index)}
                  onChange={editSteps}
                  onName={(identifier, name) => setNames((known) => ({ ...known, [identifier]: name }))}
                  onDrop={dropAt}
                />
              ))}
            </div>
            {open.length > 0 ? (
              <p className="hint" data-testid="steps-unfinished">
                {open.length} {open.length === 1 ? "step is" : "steps are"} not finished yet.
              </p>
            ) : null}
          </DropZone>
        </div>
        <p className="hint">
          At most {kinds.limits.maxSteps} steps in a process and {kinds.limits.maxApproversPerStep} rules in a step. A person
          who loses their licence, or leaves, is not asked; if nobody can decide the first step the record cannot be
          submitted, and for a later step an administrator assigns someone.
        </p>
      </section>

      <section aria-labelledby="approval-fallback-heading" className="card">
        <h3 id="approval-fallback-heading">If nobody can be asked (optional)</h3>
        <div className="record-grid record-grid-two">
          <div className="field">
            <label htmlFor="approval-fallback-kind">Fallback approver</label>
            <select
              id="approval-fallback-kind"
              value={current.fallback.kind}
              onChange={(event) => edit({ fallback: { kind: event.target.value, value: "" } })}
            >
              <option value="">None</option>
              {fallbackKinds.map((one) => (
                <option key={one.kind} value={one.kind}>
                  {humanize(one.kind)}
                </option>
              ))}
            </select>
          </div>
          {current.fallback.kind !== "" ? (
            kinds.approverKinds.find((one) => one.kind === current.fallback.kind)?.valueRefersTo === "USER" ? (
              <PersonPicker
                id="approval-fallback-person"
                label="Person"
                value={current.fallback.value}
                name={names[current.fallback.value]}
                onChange={(identifier, name) => {
                  setNames((known) => ({ ...known, [identifier]: name }));
                  edit({ fallback: { kind: current.fallback.kind, value: identifier } });
                }}
              />
            ) : (
              <div className="field">
                <label htmlFor="approval-fallback-value">Group</label>
                <select
                  id="approval-fallback-value"
                  value={current.fallback.value}
                  onChange={(event) => edit({ fallback: { kind: current.fallback.kind, value: event.target.value } })}
                >
                  <option value="">Choose…</option>
                  {(choices?.groups ?? []).map((one) => (
                    <option key={one.id} value={one.id}>
                      {one.name}
                    </option>
                  ))}
                </select>
              </div>
            )
          ) : null}
        </div>
        <p className="hint">
          The fallback is asked when the rules of a step name nobody who can decide. Without one, a record whose first step
          names nobody cannot be submitted.
        </p>
      </section>

      <section aria-labelledby="approval-outcomes-heading" className="card" data-testid="outcomes">
        <h3 id="approval-outcomes-heading">When it ends</h3>
        <p className="hint">
          Fields of the record set when the request ends, in the same moment the record is unlocked, through every rule a
          person&apos;s change goes through. A mail, a new record or an event after the decision is a workflow that starts when
          the approval ends.
        </p>
        {kinds.outcomes.map((outcome) => {
          const rows = current.outcomes[outcome.code] ?? [];
          return (
            <fieldset className="field" key={outcome.code} data-testid={`outcome-${outcome.code}`}>
              <legend>{humanize(outcome.code)}</legend>
              <p className="hint">{outcome.summary}</p>
              {rows.map((row, at) => {
                const id = `outcome-${outcome.code}-${row.id}`;
                return (
                  <div className="filter-row" key={row.id} role="group" aria-label={`${humanize(outcome.code)} field ${at + 1}`}>
                    <label className="visually-hidden" htmlFor={`${id}-field`}>
                      Field to set
                    </label>
                    <select
                      id={`${id}-field`}
                      value={row.field}
                      onChange={(event) =>
                        edit({ outcomes: changeOutcomeField(current.outcomes, outcome.code, at, { field: event.target.value }) })
                      }
                    >
                      <option value="">Choose a field</option>
                      {settable.map((field) => (
                        <option key={field.apiName} value={field.apiName}>
                          {field.label}
                        </option>
                      ))}
                    </select>
                    <label className="visually-hidden" htmlFor={`${id}-value`}>
                      Value
                    </label>
                    <input
                      id={`${id}-value`}
                      value={row.value}
                      placeholder="Value, written as a default value is"
                      onChange={(event) =>
                        edit({ outcomes: changeOutcomeField(current.outcomes, outcome.code, at, { value: event.target.value }) })
                      }
                    />
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => edit({ outcomes: removeOutcomeField(current.outcomes, outcome.code, at) })}
                    >
                      Remove
                    </button>
                  </div>
                );
              })}
              <div>
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={() => edit({ outcomes: addOutcomeField(current.outcomes, outcome.code) })}
                >
                  Add a field
                </button>
              </div>
            </fieldset>
          );
        })}
        {unfinishedOutcomes(current) > 0 ? (
          <p className="hint" data-testid="outcomes-unfinished">
            {unfinishedOutcomes(current)} field(s) are not finished yet.
          </p>
        ) : null}
        {outcomeProblems.length > 0 ? (
          <ul className="form-message" role="alert" data-testid="outcome-problems">
            {outcomeProblems.map((text) => (
              <li key={text}>{text}</li>
            ))}
          </ul>
        ) : null}
        <p className="hint">At most {kinds.limits.maxOutcomeFields} fields in an outcome.</p>
      </section>

      <section aria-labelledby="approval-who-heading" className="card" data-testid="who-would-be-asked">
        <h3 id="approval-who-heading">Who would be asked?</h3>
        <p className="hint">
          Name a record you can read to see, step by step, who the process would ask, who is left out and why. Nothing is
          stored, locked or sent. The record&apos;s identifier is in the address of its page.
        </p>
        <div className="filter-row">
          <label htmlFor="approval-record">Record identifier</label>
          <input id="approval-record" value={recordId} onChange={(event) => setRecordId(event.target.value)} />
          <button
            type="button"
            className="button button-secondary"
            disabled={recordId.trim() === "" || current.steps.length === 0}
            onClick={() => void whoWouldBeAsked()}
          >
            Work it out
          </button>
        </div>
        {resolveFailure ? (
          <p className="form-message" role="alert" data-testid="resolve-failed">
            {resolveFailure}
          </p>
        ) : null}
        {resolution ? (
          <ol data-testid="resolution">
            {resolution.steps.map((one) => (
              <li key={one.name}>
                <strong>{one.label}</strong>:{" "}
                {one.nobody
                  ? "nobody can be asked."
                  : one.people.map((person) => person.name ?? person.userId).join(", ")}
                {one.usesFallback ? " (the fallback approver)" : ""}
                {Object.keys(one.left).length > 0
                  ? ` Left out: ${Object.entries(one.left)
                      .map(([reason, count]) => `${count} ${humanize(reason).toLowerCase()}`)
                      .join(", ")}.`
                  : ""}
              </li>
            ))}
          </ol>
        ) : null}
      </section>

      <section aria-labelledby="approval-check-heading" className="stack" role="status" aria-live="polite">
        <h3 id="approval-check-heading" className="visually-hidden">
          Result of checking the process
        </h3>
        {checkFailure ? (
          <p className="form-message" role="alert" data-testid="approval-check-failed">
            {checkFailure}
          </p>
        ) : null}
        {checked && Object.keys(checked.problems).length === 0 ? (
          <p data-testid="approval-check-ok">The steps and the outcomes fit the object. Nothing was saved.</p>
        ) : null}
        {!creating ? (
          <p className="hint">
            <Link href={`/approvals?object=${encodeURIComponent(objectApiName)}&process=${encodeURIComponent(processApiName)}`}>
              See the requests under this process
            </Link>
          </p>
        ) : null}
      </section>
    </BuilderShell>
  );
}
