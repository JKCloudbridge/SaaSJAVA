"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { Author } from "./authorship";
import { ExpressionCheck } from "./ExpressionCheck";
import { detailParents, listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type RuleView = components["schemas"]["ValidationRuleView"];

interface Draft {
  name: string;
  label: string;
  description: string;
  expression: string;
  errorMessage: string;
  errorField: string;
  active: boolean;
}

const EMPTY: Draft = { name: "", label: "", description: "", expression: "", errorMessage: "", errorField: "", active: true };

const ROUTE = { openParam: "rule", keep: ["section"] } as const;

function draftOf(rule: RuleView): Draft {
  return {
    name: rule.apiName,
    label: rule.label,
    description: rule.description,
    expression: rule.expression,
    errorMessage: rule.errorMessage,
    errorField: rule.errorField ?? "",
    active: rule.active,
  };
}

/**
 * The Validation rules tab of an object (UA3, ADR-0170, ADR-0078): a rule is a condition in the expression language that,
 * when it is true, rejects the save, and the message the person who is rejected reads. A list (rule, API name, status); a
 * row opens the rule's page; New opens the form with the test box. Changes go live (one step in the history) or, when a
 * change set is chosen, into it. The page shows what the API answers: whether the condition is valid, which fields exist
 * and what depends on what is the API's to decide, and it names the place that is wrong. The test box asks the real
 * engine; nothing is evaluated in the browser.
 */
export function ValidationRulesPanel({ object, reload: reloadObject, authorship }: ObjectTabProps) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");
  const [rules, setRules] = useState<RuleView[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);

  const load = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/validation-rules", {
        params: { path: { objectApiName } },
      });
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setRules(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(load);
    }
  }, [load, managed]);

  const done = useCallback(async () => {
    await load();
    await reloadObject();
  }, [load, reloadObject]);
  const screen = useListScreen(done, ROUTE);
  const { busy, notice, problem, act } = screen;

  const messages = <ActionMessages notice={notice} problem={problem} testId="rules" />;
  const crumbs = listCrumbs(object, "Validation rules");
  const parents = detailParents(object);
  // A message is shown on a field a person fills in, never on one of the platform's system fields.
  const fields = object.fields.filter((field) => !field.retired && field.kind !== "SYSTEM");
  const fieldLabel = (apiName?: string) =>
    apiName ? (object.fields.find((field) => field.apiName === apiName)?.label ?? apiName) : "The record as a whole";

  function update(rule: RuleView, change: Partial<Draft>) {
    const next = { ...draftOf(rule), ...change };
    return {
      label: next.label,
      description: next.description,
      expression: next.expression,
      errorMessage: next.errorMessage,
      errorField: next.errorField === "" ? undefined : next.errorField,
      active: next.active,
      version: rule.version,
    };
  }

  function send(rule: RuleView, change: Partial<Draft>, text: string) {
    void act(async () => {
      const body = update(rule, change);
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "UPDATE_VALIDATION_RULE",
            objectApiName,
            itemApiName: rule.apiName,
            updateValidationRule: body,
          })
        : await api.PUT("/api/v1/metadata/objects/{objectApiName}/validation-rules/{ruleApiName}", {
            params: { path: { objectApiName, ruleApiName: rule.apiName } },
            body,
          });
      if (result.response.ok && screen.openId === rule.apiName) {
        setEditing(false);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : text,
        problem: problemText(result.error, result.response),
      };
    });
  }

  const save = (rule: RuleView) => (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    send(rule, draft, "The validation rule was saved.");
  };

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const request = {
        name: draft.name,
        label: draft.label,
        description: draft.description,
        expression: draft.expression,
        errorMessage: draft.errorMessage,
        errorField: draft.errorField === "" ? undefined : draft.errorField,
        active: draft.active,
      };
      const result = workingSet
        ? await addToSet(workingSet, { kind: "CREATE_VALIDATION_RULE", objectApiName, createValidationRule: request })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/validation-rules", {
            params: { path: { objectApiName } },
            body: request,
          });
      if (result.response.ok) {
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The validation rule was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const remove = (rule: RuleView) => {
    setConfirmRemove(false);
    return act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_VALIDATION_RULE", objectApiName, itemApiName: rule.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/validation-rules/{ruleApiName}", {
            params: { path: { objectApiName, ruleApiName: rule.apiName } },
          });
      if (result.response.ok && screen.openId === rule.apiName) {
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The validation rule was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  };

  const form = (isEditing: boolean) => (
    <RuleFields draft={draft} onDraft={setDraft} object={object} fields={fields} editing={isEditing} />
  );

  if (object.managedBy) {
    return (
      <ListPage<RuleView>
        title={tabTitle(object, "Validation rules")}
        crumbs={crumbs}
        items={[]}
        rowKey={(row) => row.apiName}
        primaryHeader="Rule"
        primary={(row) => row.label}
        columns={[]}
        emptyText="The records of this object belong to the platform, so it has no validation rules."
        testId="rules"
      />
    );
  }

  if (screen.creating && rules) {
    return (
      <DetailPage
        listLabel="Validation rules"
        parents={parents}
        onBack={() => screen.back()}
        title="New validation rule"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={() => screen.back()}>
              Cancel
            </button>
            <button type="submit" form="rule-form" className="button" disabled={busy}>
              Add the validation rule
            </button>
          </>
        }
        testId="rule-new"
      >
        <form id="rule-form" onSubmit={create} noValidate aria-label="Add a validation rule">
          <DetailSection title="Detail" stack>
            {form(false)}
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const rule = rules?.find((candidate) => candidate.apiName === screen.openId);
  if (screen.openId && rules && !rule) {
    return (
      <MissingDetail
        listLabel="Validation rules"
        onBack={() => screen.back()}
        what="This validation rule does not exist any more."
      />
    );
  }

  if (rule) {
    const actions = editing ? (
      <>
        <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
          Cancel
        </button>
        <button type="submit" form="rule-form" className="button" disabled={busy}>
          Save the validation rule
        </button>
      </>
    ) : offersManage ? (
      <>
        <button
          type="button"
          className="button button-secondary"
          disabled={busy}
          onClick={() =>
            send(rule, { active: !rule.active }, rule.active ? "The rule was switched off." : "The rule was switched on.")
          }
        >
          {rule.active ? "Switch off" : "Switch on"}
        </button>
        <button
          type="button"
          className="button button-danger"
          disabled={busy}
          onClick={() => setConfirmRemove((value) => !value)}
        >
          Remove
        </button>
        <button
          type="button"
          className="button"
          disabled={busy}
          onClick={() => {
            setDraft(draftOf(rule));
            setEditing(true);
          }}
        >
          Edit
        </button>
      </>
    ) : null;
    return (
      <DetailPage
        listLabel="Validation rules"
        parents={parents}
        onBack={() => {
          setEditing(false);
          setConfirmRemove(false);
          screen.back();
        }}
        title={rule.label}
        actions={actions}
        notices={messages}
        testId="rule-detail"
      >
        {confirmRemove ? (
          <p role="alertdialog" aria-label="Remove the validation rule" data-testid="rule-remove-confirm">
            Remove this validation rule?{" "}
            <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(rule)}>
              Yes, remove the rule
            </button>{" "}
            <button type="button" className="link-button" onClick={() => setConfirmRemove(false)}>
              Cancel
            </button>
          </p>
        ) : null}
        {editing ? (
          <form id="rule-form" onSubmit={save(rule)} noValidate aria-label="Change the validation rule">
            <DetailSection title="Edit validation rule" stack>
              {form(true)}
            </DetailSection>
          </form>
        ) : (
          <>
            <DetailSection title="Detail">
              <DetailGrid>
                <DetailItem label="Label">{rule.label}</DetailItem>
                <DetailItem label="API name" tone="mono">
                  {rule.apiName}
                </DetailItem>
                <DetailItem label="Description" wide tone={rule.description ? undefined : "muted"}>
                  {rule.description || "—"}
                </DetailItem>
                <DetailItem label="Status">
                  <Tag tone={rule.active ? "ok" : "quiet"}>{rule.active ? "Active" : "Not active"}</Tag>
                </DetailItem>
                <DetailItem label="Object name">{object.label}</DetailItem>
                <DetailItem label="Created by">
                  <Author authorship={authorship} kind="VALIDATION_RULE" apiName={rule.apiName} which="created" />
                </DetailItem>
                <DetailItem label="Modified by">
                  <Author authorship={authorship} kind="VALIDATION_RULE" apiName={rule.apiName} which="updated" />
                </DetailItem>
              </DetailGrid>
            </DetailSection>
            <DetailSection title="Rejects when">
              <DetailGrid>
                <DetailItem label="Condition" wide tone="mono">
                  {rule.expression}
                </DetailItem>
                <DetailItem label="Message" wide>
                  {rule.errorMessage}
                </DetailItem>
                <DetailItem label="Shown on">{fieldLabel(rule.errorField)}</DetailItem>
              </DetailGrid>
            </DetailSection>
          </>
        )}
      </DetailPage>
    );
  }

  return (
    <ListPage<RuleView>
      title={tabTitle(object, "Validation rules")}
      crumbs={crumbs}
      items={rules}
      failure={failure}
      rowKey={(row) => row.apiName}
      primaryHeader="Rule"
      primary={(row) => row.label}
      primaryExtra={(row) => (row.description ? <span className="hint"> {row.description}</span> : null)}
      columns={[
        { key: "api", header: "API name", kind: "muted", cell: (row) => <code>{row.apiName}</code> },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => <Tag tone={row.active ? "ok" : "quiet"}>{row.active ? "Active" : "Not active"}</Tag>,
        },
      ]}
      onOpen={(row) => {
        setEditing(false);
        setConfirmRemove(false);
        screen.open(row.apiName);
      }}
      searchText={(row) => `${row.label} ${row.apiName}`}
      searchLabel="Search validation rules"
      onNew={
        offersManage
          ? () => {
              setDraft(EMPTY);
              setEditing(false);
              screen.create();
            }
          : undefined
      }
      notices={messages}
      emptyText="No validation rules yet."
      testId="rules"
      rowTestId="rule-row"
    />
  );
}

/** The fields of a validation rule as a form, with the test box beside the condition. */
function RuleFields({
  draft,
  onDraft,
  object,
  fields,
  editing,
}: {
  draft: Draft;
  onDraft: (next: Draft) => void;
  object: ObjectView;
  fields: ObjectView["fields"];
  editing: boolean;
}) {
  return (
    <>
      {editing ? (
        <p>
          <code>{draft.name}</code>. The name of a validation rule never changes.
        </p>
      ) : (
        <div className="field">
          <label htmlFor="rule-name">Name</label>
          <input
            id="rule-name"
            value={draft.name}
            maxLength={40}
            onChange={(event) => onDraft({ ...draft, name: event.target.value })}
            required
            aria-describedby="rule-name-hint"
          />
          <p className="hint" id="rule-name-hint">
            The permanent API name, starting with a capital letter, for example CloseDateRequired. The platform adds the ending
            __c.
          </p>
        </div>
      )}
      <div className="field">
        <label htmlFor="rule-label">Label</label>
        <input
          id="rule-label"
          value={draft.label}
          maxLength={80}
          onChange={(event) => onDraft({ ...draft, label: event.target.value })}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="rule-description">Description</label>
        <input
          id="rule-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => onDraft({ ...draft, description: event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="rule-expression">Condition</label>
        <textarea
          id="rule-expression"
          value={draft.expression}
          maxLength={4000}
          onChange={(event) => onDraft({ ...draft, expression: event.target.value })}
          aria-describedby="rule-expression-hint"
        />
        <p className="hint" id="rule-expression-hint">
          The save is rejected when this is true, for example: status__c = &apos;Closed&apos; AND ISBLANK(closeDate__c)
        </p>
      </div>
      <ExpressionCheck
        objectApiName={object.apiName}
        expression={draft.expression}
        purpose="VALIDATION_RULE"
        ruleMessage={draft.errorMessage}
        fields={object.fields}
        testId="rule-check"
      />
      <div className="field">
        <label htmlFor="rule-message">Message</label>
        <input
          id="rule-message"
          value={draft.errorMessage}
          maxLength={255}
          onChange={(event) => onDraft({ ...draft, errorMessage: event.target.value })}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="rule-error-field">Show the message on</label>
        <select
          id="rule-error-field"
          value={draft.errorField}
          onChange={(event) => onDraft({ ...draft, errorField: event.target.value })}
        >
          <option value="">The record as a whole</option>
          {fields.map((field) => (
            <option key={field.apiName} value={field.apiName}>
              {field.label} ({field.apiName})
            </option>
          ))}
        </select>
      </div>
      <label>
        <input type="checkbox" checked={draft.active} onChange={(event) => onDraft({ ...draft, active: event.target.checked })} />{" "}
        Active
      </label>
    </>
  );
}
