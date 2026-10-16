"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import {
  CRITERIA_OPERATORS,
  criteriaFrom,
  criteriaNode,
  describeCriteria,
  emptyCriteria,
  type CriteriaDraft,
} from "@/lib/builder/sharingCriteria";
import { problemText } from "./objectText";

type ObjectView = components["schemas"]["ObjectView"];
type ObjectSharingView = components["schemas"]["ObjectSharingView"];
type SharingRuleView = components["schemas"]["SharingRuleView"];
type RoleView = components["schemas"]["RoleView"];
type GroupView = components["schemas"]["GroupView"];

const DEFAULT_TEXT: Record<string, { label: string; explanation: string }> = {
  PRIVATE: {
    label: "Private",
    explanation:
      "Members reach only the records they own, the records of the people below them (when the hierarchy switch is on), the records a rule gives them and the records shared with them.",
  },
  PUBLIC_READ: {
    label: "Public read only",
    explanation: "Every member who may read the object reads every record. Changing a record needs ownership or a share.",
  },
  PUBLIC_READ_WRITE: {
    label: "Public read and write",
    explanation: "Every member who may read the object reads and changes every record.",
  },
};

const KIND_TEXT: Record<string, string> = {
  OWNER: "By owner",
  GROUP: "By group",
  CRITERIA: "By condition",
};

const PRINCIPAL_TEXT: Record<string, string> = {
  ROLE: "a role",
  ROLE_AND_SUBORDINATES: "a role and the roles below it",
  GROUP: "a public group",
};

const LEVEL_TEXT: Record<string, string> = { READ: "Read only", READ_WRITE: "Read and write" };

interface RuleDraft {
  name: string;
  kind: "OWNER" | "GROUP" | "CRITERIA";
  sourceType: string;
  sourceId: string;
  targetType: string;
  targetId: string;
  accessLevel: string;
  active: boolean;
  criteria: CriteriaDraft;
}

const EMPTY_RULE: RuleDraft = {
  name: "",
  kind: "OWNER",
  sourceType: "ROLE",
  sourceId: "",
  targetType: "ROLE",
  targetId: "",
  accessLevel: "READ",
  active: true,
  criteria: emptyCriteria(),
};

interface Loaded {
  sharing: ObjectSharingView;
  rules: SharingRuleView[];
  roles: RoleView[];
  groups: GroupView[];
}

/**
 * Who reaches the records of one object (Sprint 17): the organization-wide default, whether the role hierarchy gives
 * access, and the sharing rules (by owner, by group, by condition) that give more. A change takes effect at once for
 * everyone and is not a draft: sharing is not part of the metadata that change sets carry, so this screen ignores the
 * chosen change set and says so. The page shows what the API answers; whether the person may manage sharing, and what a
 * rule is allowed to say, is the API's to decide.
 */
export function SharingPanel({ object }: { object: ObjectView }) {
  const objectApiName = object.apiName;
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [defaults, setDefaults] = useState({ defaultAccess: "PUBLIC_READ_WRITE", grantViaHierarchy: true });
  const [draft, setDraft] = useState<RuleDraft>(EMPTY_RULE);
  const [editing, setEditing] = useState<SharingRuleView | undefined>();
  const [opening, setOpening] = useState(false);

  const reload = useCallback(async () => {
    try {
      const [sharing, rules, roles, groups] = await Promise.all([
        api.GET("/api/v1/sharing/objects/{objectApiName}", { params: { path: { objectApiName } } }),
        api.GET("/api/v1/sharing/rules", { params: { query: { object: objectApiName } } }),
        api.GET("/api/v1/roles"),
        api.GET("/api/v1/groups"),
      ]);
      if (!sharing.data || !rules.data) {
        const first = sharing.data ? rules : sharing;
        setFailure(await failureText(first.error, first.response));
        return;
      }
      setFailure(undefined);
      setLoaded({
        sharing: sharing.data.data,
        rules: rules.data.data,
        roles: roles.data?.data ?? [],
        groups: groups.data?.data ?? [],
      });
      setDefaults({
        defaultAccess: sharing.data.data.defaultAccess,
        grantViaHierarchy: sharing.data.data.grantViaHierarchy ?? true,
      });
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

  const { busy, notice, problem, act } = useAction(reload);

  if (object.managedBy) {
    return (
      <section aria-labelledby="sharing-heading">
        <h3 id="sharing-heading">Sharing</h3>
        <p className="hint">The records of this object belong to the platform, so their sharing is the platform&apos;s.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="sharing-failed">
        {failure}
      </p>
    );
  }
  if (!loaded) {
    return <p aria-live="polite">Loading…</p>;
  }

  const fields = object.fields.filter(
    (field) => field.kind !== "SYSTEM" && !field.retired && (field.indexed || field.unique || field.externalId),
  );
  const labelOf = (name: string) => object.fields.find((field) => field.apiName === name)?.label ?? name;
  const typeOf = (name: string) => object.fields.find((field) => field.apiName === name)?.type;
  const roleName = (id: string | undefined) => loaded.roles.find((one) => one.id === id)?.name ?? "A role that was removed";
  const groupName = (id: string | undefined) => loaded.groups.find((one) => one.id === id)?.name ?? "A group that was removed";
  const principalName = (type: string | undefined, id: string | undefined) =>
    type === "GROUP" ? groupName(id) : `${roleName(id)}${type === "ROLE_AND_SUBORDINATES" ? " and below" : ""}`;

  function saveDefaults(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const body = { ...defaults, ...(loaded?.sharing.configured ? { version: loaded.sharing.version } : {}) };
      const result = await api.PUT("/api/v1/sharing/objects/{objectApiName}", {
        params: { path: { objectApiName } },
        body,
      });
      return {
        error: result.error,
        response: result.response,
        text: "The default access was saved. It applies to every member now.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function reset() {
    setDraft(EMPTY_RULE);
    setEditing(undefined);
    setOpening(false);
  }

  function startEdit(rule: SharingRuleView) {
    setEditing(rule);
    setOpening(true);
    setDraft({
      name: rule.name,
      kind: rule.kind as RuleDraft["kind"],
      sourceType: rule.sourceType ?? "ROLE",
      sourceId: rule.sourceId ?? "",
      targetType: rule.targetType,
      targetId: rule.targetId,
      accessLevel: rule.accessLevel,
      active: rule.active ?? true,
      criteria: criteriaFrom(rule.criterion),
    });
  }

  function saveRule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = {
      name: draft.name,
      kind: draft.kind,
      object: objectApiName,
      ...(draft.kind === "OWNER" ? { sourceType: draft.sourceType, sourceId: draft.sourceId || undefined } : {}),
      ...(draft.kind === "GROUP" ? { sourceId: draft.sourceId || undefined } : {}),
      ...(draft.kind === "CRITERIA" ? { criterion: criteriaNode(draft.criteria, typeOf) } : {}),
      targetType: draft.targetType,
      targetId: draft.targetId,
      accessLevel: draft.accessLevel,
      active: draft.active,
    };
    void act(async () => {
      if (editing) {
        const result = await api.PUT("/api/v1/sharing/rules/{ruleId}", {
          params: { path: { ruleId: editing.id } },
          body: { ...body, version: editing.version },
        });
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: "The rule was saved. It applies at the next question of every member.",
          problem: problemText(result.error, result.response),
        };
      }
      const result = await api.POST("/api/v1/sharing/rules", { body });
      if (result.response.ok) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: "The rule was added. It applies at the next question of every member.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function removeRule(rule: SharingRuleView) {
    void act(async () => {
      const result = await api.DELETE("/api/v1/sharing/rules/{ruleId}", { params: { path: { ruleId: rule.id } } });
      return {
        error: result.error,
        response: result.response,
        text: "The rule was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function sourceOf(rule: SharingRuleView): string {
    if (rule.kind === "CRITERIA") {
      return `Records where ${describeCriteria(rule.criterion, labelOf)}`;
    }
    return `Records owned by ${principalName(rule.sourceType, rule.sourceId)}`;
  }

  const setting = DEFAULT_TEXT[defaults.defaultAccess];
  const formName = editing ? "Change the sharing rule" : "New sharing rule";

  return (
    <section aria-labelledby="sharing-heading" className="stack" data-testid="sharing">
      <h3 id="sharing-heading">Sharing</h3>
      <p className="hint" data-testid="sharing-live-note">
        Sharing takes effect at once for every member. It is not part of a change set: the change set chosen above does not
        hold these changes.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="sharing" />

      <div className="detail-section">
        <h4>Who reaches the records</h4>
        <form className="form" onSubmit={saveDefaults} aria-label="Default access" noValidate>
          <div className="field">
            <label htmlFor="sharing-default">Default access of the organization</label>
            <select
              id="sharing-default"
              value={defaults.defaultAccess}
              onChange={(event) => setDefaults({ ...defaults, defaultAccess: event.target.value })}
            >
              {Object.entries(DEFAULT_TEXT).map(([value, text]) => (
                <option key={value} value={value}>
                  {text.label}
                </option>
              ))}
            </select>
            <p className="hint">{setting?.explanation}</p>
          </div>
          <label>
            <input
              type="checkbox"
              checked={defaults.grantViaHierarchy}
              onChange={(event) => setDefaults({ ...defaults, grantViaHierarchy: event.target.checked })}
            />{" "}
            Give access through the role hierarchy (the people above someone read their records)
          </label>
          {loaded.sharing.configured ? null : (
            <p className="hint">
              Nobody has set this object up yet, so every member who may read the object reads every record.
            </p>
          )}
          <p className="hint">
            Members who hold &ldquo;view all&rdquo; or &ldquo;modify all&rdquo; on the object are not held by the default.
          </p>
          <div className="form-actions">
            <button type="submit" className="button" disabled={busy}>
              Save the default access
            </button>
          </div>
        </form>
      </div>

      <div className="detail-section">
        <div className="list-head">
          <h4>Sharing rules</h4>
          <button
            type="button"
            className="button"
            onClick={() => {
              setEditing(undefined);
              setDraft(EMPTY_RULE);
              setOpening(true);
            }}
          >
            New sharing rule
          </button>
        </div>
        {loaded.rules.length === 0 ? (
          <p>No sharing rules yet. A rule gives some members read or write access to records they would not reach otherwise.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Rule</th>
                <th>Type</th>
                <th>Records</th>
                <th>Shared with</th>
                <th>Access</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {loaded.rules.map((rule) => (
                <tr key={rule.id} data-testid="sharing-rule-row">
                  <td>
                    {rule.name}
                    {rule.active === false ? <span className="badge"> Off</span> : null}
                  </td>
                  <td>{KIND_TEXT[rule.kind] ?? rule.kind}</td>
                  <td>{sourceOf(rule)}</td>
                  <td>{principalName(rule.targetType, rule.targetId)}</td>
                  <td>{LEVEL_TEXT[rule.accessLevel] ?? rule.accessLevel}</td>
                  <td>
                    <button type="button" className="link-button" disabled={busy} onClick={() => startEdit(rule)}>
                      Change
                    </button>{" "}
                    <button type="button" className="link-button" disabled={busy} onClick={() => removeRule(rule)}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {opening ? (
        <div className="card">
          <h4>{formName}</h4>
          <form className="form" onSubmit={saveRule} noValidate aria-label={formName}>
            <div className="field">
              <label htmlFor="rule-name">Name</label>
              <input
                id="rule-name"
                value={draft.name}
                maxLength={80}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              />
            </div>
            {editing ? (
              <p>{KIND_TEXT[editing.kind]}. The type of a rule never changes.</p>
            ) : (
              <div className="field">
                <label htmlFor="rule-kind">Which records</label>
                <select
                  id="rule-kind"
                  value={draft.kind}
                  onChange={(event) => setDraft({ ...draft, kind: event.target.value as RuleDraft["kind"], sourceId: "" })}
                >
                  <option value="OWNER">Records owned by the members of a role</option>
                  <option value="GROUP">Records owned by the members of a public group</option>
                  <option value="CRITERIA">Records that meet a condition</option>
                </select>
              </div>
            )}

            {draft.kind === "OWNER" ? (
              <>
                <div className="field">
                  <label htmlFor="rule-source-type">Owned by</label>
                  <select
                    id="rule-source-type"
                    value={draft.sourceType}
                    onChange={(event) => setDraft({ ...draft, sourceType: event.target.value })}
                  >
                    <option value="ROLE">The members of a role</option>
                    <option value="ROLE_AND_SUBORDINATES">The members of a role and the roles below it</option>
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="rule-source">Role</label>
                  <select
                    id="rule-source"
                    value={draft.sourceId}
                    onChange={(event) => setDraft({ ...draft, sourceId: event.target.value })}
                  >
                    <option value="">Choose a role</option>
                    {loaded.roles.map((one) => (
                      <option key={one.id} value={one.id}>
                        {one.name}
                      </option>
                    ))}
                  </select>
                </div>
              </>
            ) : null}
            {draft.kind === "GROUP" ? (
              <div className="field">
                <label htmlFor="rule-source">Public group</label>
                <select
                  id="rule-source"
                  value={draft.sourceId}
                  onChange={(event) => setDraft({ ...draft, sourceId: event.target.value })}
                >
                  <option value="">Choose a public group</option>
                  {loaded.groups.map((one) => (
                    <option key={one.id} value={one.id}>
                      {one.name}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            {draft.kind === "CRITERIA" ? (
              <fieldset className="field">
                <legend>The condition (on fields marked as indexed)</legend>
                {draft.criteria.kind === "kept" ? (
                  <p>
                    {describeCriteria(draft.criteria.node, labelOf)}.{" "}
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => setDraft({ ...draft, criteria: emptyCriteria() })}
                    >
                      Start a new condition
                    </button>
                  </p>
                ) : (
                  <CriteriaRows
                    criteria={draft.criteria}
                    fields={fields.map((field) => ({ apiName: field.apiName, label: field.label, type: field.type }))}
                    onChange={(criteria) => setDraft({ ...draft, criteria })}
                  />
                )}
              </fieldset>
            ) : null}

            <div className="field">
              <label htmlFor="rule-target-type">Shared with</label>
              <select
                id="rule-target-type"
                value={draft.targetType}
                onChange={(event) => setDraft({ ...draft, targetType: event.target.value, targetId: "" })}
              >
                {Object.entries(PRINCIPAL_TEXT).map(([value, text]) => (
                  <option key={value} value={value}>
                    {text[0]?.toUpperCase()}
                    {text.slice(1)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="rule-target">{draft.targetType === "GROUP" ? "Public group" : "Role"} that gets access</label>
              <select
                id="rule-target"
                value={draft.targetId}
                onChange={(event) => setDraft({ ...draft, targetId: event.target.value })}
              >
                <option value="">Choose one</option>
                {(draft.targetType === "GROUP" ? loaded.groups : loaded.roles).map((one) => (
                  <option key={one.id} value={one.id}>
                    {one.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="rule-level">Access</label>
              <select
                id="rule-level"
                value={draft.accessLevel}
                onChange={(event) => setDraft({ ...draft, accessLevel: event.target.value })}
              >
                <option value="READ">Read only</option>
                <option value="READ_WRITE">Read and write</option>
              </select>
              <p className="hint">A rule never gives the right to delete a record.</p>
            </div>
            <label>
              <input
                type="checkbox"
                checked={draft.active}
                onChange={(event) => setDraft({ ...draft, active: event.target.checked })}
              />{" "}
              The rule is on
            </label>
            <div className="form-actions">
              <button type="submit" className="button" disabled={busy}>
                {editing ? "Save the rule" : "Add the rule"}
              </button>
              <button type="button" className="link-button" onClick={reset}>
                Cancel
              </button>
            </div>
          </form>
        </div>
      ) : null}
    </section>
  );
}

/** The rows of a condition: field, comparison, value; all or any of them. */
function CriteriaRows({
  criteria,
  fields,
  onChange,
}: {
  criteria: Extract<CriteriaDraft, { kind: "rows" }>;
  fields: { apiName: string; label: string; type: string }[];
  onChange: (next: CriteriaDraft) => void;
}) {
  function change(index: number, part: Partial<(typeof criteria.rows)[number]>) {
    onChange({ ...criteria, rows: criteria.rows.map((row, at) => (at === index ? { ...row, ...part } : row)) });
  }
  return (
    <>
      {fields.length === 0 ? (
        <p className="hint">
          No field of this object is marked as indexed, so none can be used in a condition. Mark a field as indexed on the
          Fields page first.
        </p>
      ) : null}
      {criteria.rows.length > 1 ? (
        <div className="field">
          <label htmlFor="criteria-match">The records must meet</label>
          <select
            id="criteria-match"
            value={criteria.match}
            onChange={(event) => onChange({ ...criteria, match: event.target.value === "OR" ? "OR" : "AND" })}
          >
            <option value="AND">All of the conditions</option>
            <option value="OR">Any of the conditions</option>
          </select>
        </div>
      ) : null}
      {criteria.rows.map((row, index) => {
        const takesValue = CRITERIA_OPERATORS.find((one) => one.value === row.op)?.takesValue ?? true;
        const isBoolean = fields.find((field) => field.apiName === row.field)?.type === "BOOLEAN";
        return (
          <div className="filter-row" key={index} data-testid="criteria-row">
            <div className="field">
              <label htmlFor={`criteria-field-${index}`}>Field</label>
              <select
                id={`criteria-field-${index}`}
                value={row.field}
                onChange={(event) => change(index, { field: event.target.value })}
              >
                <option value="">Choose a field</option>
                {fields.map((field) => (
                  <option key={field.apiName} value={field.apiName}>
                    {field.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor={`criteria-op-${index}`}>Comparison</label>
              <select id={`criteria-op-${index}`} value={row.op} onChange={(event) => change(index, { op: event.target.value })}>
                {CRITERIA_OPERATORS.map((one) => (
                  <option key={one.value} value={one.value}>
                    {one.text}
                  </option>
                ))}
              </select>
            </div>
            {takesValue ? (
              <div className="field">
                <label htmlFor={`criteria-value-${index}`}>Value</label>
                {isBoolean ? (
                  <select
                    id={`criteria-value-${index}`}
                    value={row.value}
                    onChange={(event) => change(index, { value: event.target.value })}
                  >
                    <option value="">Choose</option>
                    <option value="true">Yes</option>
                    <option value="false">No</option>
                  </select>
                ) : (
                  <input
                    id={`criteria-value-${index}`}
                    value={row.value}
                    onChange={(event) => change(index, { value: event.target.value })}
                  />
                )}
              </div>
            ) : null}
            {criteria.rows.length > 1 ? (
              <button
                type="button"
                className="link-button"
                onClick={() => onChange({ ...criteria, rows: criteria.rows.filter((_, at) => at !== index) })}
              >
                Take out condition {index + 1}
              </button>
            ) : null}
          </div>
        );
      })}
      <button
        type="button"
        className="link-button"
        onClick={() => onChange({ ...criteria, rows: [...criteria.rows, { field: "", op: "EQ", value: "" }] })}
      >
        Add a condition
      </button>
    </>
  );
}
