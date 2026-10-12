"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ExpressionCheck } from "./ExpressionCheck";
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
 * The validation rules of one object. A rule is a condition in the expression language that, when it is true, rejects the
 * save, and the message the person who is rejected reads. Rules are made, changed, switched on and off and removed here
 * either live (one step in the history) or, when a change set is chosen, as drafts that go live with the set. The page
 * shows what the API answers: whether the condition is valid, which fields exist and what depends on what is the API's to
 * decide, and it names the place that is wrong. The test box asks the real engine; nothing is evaluated in the browser.
 */
export function ValidationRulesPanel({ object, onChanged }: { object: ObjectView; onChanged: () => void }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [rules, setRules] = useState<RuleView[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState<RuleView | undefined>();

  const reload = useCallback(async () => {
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
      void Promise.resolve().then(reload);
    }
  }, [reload, managed]);

  const done = useCallback(async () => {
    await reload();
    onChanged();
  }, [reload, onChanged]);
  const { busy, notice, problem, act } = useAction(done);

  if (object.managedBy) {
    return (
      <section aria-labelledby="rules-heading">
        <h3 id="rules-heading">Validation rules</h3>
        <p className="hint">The records of this object belong to the platform, so it has no validation rules.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="rules-failed">
        {failure}
      </p>
    );
  }
  if (!rules) {
    return <p aria-live="polite">Loading…</p>;
  }

  // A message is shown on a field a person fills in, never on one of the platform's system fields.
  const fields = object.fields.filter((field) => !field.retired && field.kind !== "SYSTEM");

  function reset() {
    setDraft(EMPTY);
    setEditing(undefined);
  }

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
      if (result.response.ok && editing?.apiName === rule.apiName) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : text,
        problem: problemText(result.error, result.response),
      };
    });
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (editing) {
      send(editing, draft, "The validation rule was saved.");
      return;
    }
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
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The validation rule was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(rule: RuleView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, {
            kind: "DELETE_VALIDATION_RULE",
            objectApiName,
            itemApiName: rule.apiName,
          })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/validation-rules/{ruleApiName}", {
            params: { path: { objectApiName, ruleApiName: rule.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The validation rule was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const formName = editing ? "Change the validation rule" : "Add a validation rule";

  return (
    <section aria-labelledby="rules-heading" data-testid="rules">
      <h3 id="rules-heading">Validation rules</h3>
      <p className="hint">
        A validation rule is a condition. When it is true for a record, the save is rejected and the person reads the message
        of the rule. A condition with no value does not reject.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="rules" />
      {rules.length === 0 ? (
        <p>No validation rules yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Rule</th>
              <th>API name</th>
              <th>Active</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rules.map((rule) => (
              <tr key={rule.apiName} data-testid="rule-row">
                <td>
                  {rule.label}
                  {rule.description ? <div className="hint">{rule.description}</div> : null}
                  <div className="hint">
                    Rejects when <code>{rule.expression}</code>
                  </div>
                  <div className="hint">
                    Message: {rule.errorMessage}
                    {rule.errorField ? ` (shown on ${rule.errorField})` : ""}
                  </div>
                </td>
                <td>
                  <code>{rule.apiName}</code>
                </td>
                <td>{rule.active ? "Yes" : "No"}</td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => send(rule, { active: !rule.active }, rule.active ? "The rule was switched off." : "The rule was switched on.")}
                  >
                    {rule.active ? "Switch off" : "Switch on"}
                  </button>{" "}
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => {
                      setEditing(rule);
                      setDraft(draftOf(rule));
                    }}
                  >
                    Change
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(rule)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h4>{formName}</h4>
      <form className="form" onSubmit={save} noValidate aria-label={formName}>
        {editing ? (
          <p>
            <code>{editing.apiName}</code>. The name of a validation rule never changes.
          </p>
        ) : (
          <div className="field">
            <label htmlFor="rule-name">Name</label>
            <input
              id="rule-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
              aria-describedby="rule-name-hint"
            />
            <p className="hint" id="rule-name-hint">
              The permanent name, starting with a capital letter, for example CloseDateRequired. The platform adds the ending
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
            onChange={(event) => setDraft({ ...draft, label: event.target.value })}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="rule-description">Description</label>
          <input
            id="rule-description"
            value={draft.description}
            maxLength={500}
            onChange={(event) => setDraft({ ...draft, description: event.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="rule-expression">Condition</label>
          <textarea
            id="rule-expression"
            value={draft.expression}
            maxLength={4000}
            onChange={(event) => setDraft({ ...draft, expression: event.target.value })}
            aria-describedby="rule-expression-hint"
          />
          <p className="hint" id="rule-expression-hint">
            The save is rejected when this is true, for example: status__c = &apos;Closed&apos; AND ISBLANK(closeDate__c)
          </p>
        </div>
        <ExpressionCheck
          objectApiName={objectApiName}
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
            onChange={(event) => setDraft({ ...draft, errorMessage: event.target.value })}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="rule-error-field">Show the message on</label>
          <select
            id="rule-error-field"
            value={draft.errorField}
            onChange={(event) => setDraft({ ...draft, errorField: event.target.value })}
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
          <input
            type="checkbox"
            checked={draft.active}
            onChange={(event) => setDraft({ ...draft, active: event.target.checked })}
          />{" "}
          Active
        </label>
        <div>
          <button type="submit" className="button" disabled={busy}>
            {editing ? "Save the validation rule" : "Add the validation rule"}
          </button>{" "}
          {editing ? (
            <button type="button" className="link-button" onClick={reset}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>
    </section>
  );
}
