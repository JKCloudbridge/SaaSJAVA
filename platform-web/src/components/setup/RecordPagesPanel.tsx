"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { describeRule, RecordPagePreview } from "./RecordPagePreview";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];
type RecordPageView = components["schemas"]["RecordPageView"];
type RecordPageDefinition = components["schemas"]["RecordPageDefinition"];
type PageComponent = components["schemas"]["PageComponent"];
type TabComponent = components["schemas"]["TabComponent"];
type PageAction = components["schemas"]["PageAction"];
type VisibilityRule = components["schemas"]["VisibilityRule"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type AbilityInfo = components["schemas"]["AbilityInfo"];

type Operator = NonNullable<VisibilityRule["operator"]>;

/** The rule of a component as a form can edit it; a combined rule (all/any) is kept as it is and only shown in words. */
type RuleDraft =
  | { kind: "none" }
  | { kind: "field"; field: string; operator: Operator; value: string }
  | { kind: "ability"; ability: string }
  | { kind: "kept"; rule: VisibilityRule };

interface ComponentDraft {
  key: string;
  type: PageComponent["type"];
  region: "MAIN" | "SIDEBAR";
  titleField: string;
  highlightFields: string[];
  label: string;
  object: string;
  field: string;
  columns: string[];
  limit: string;
  tabs: { label: string; components: ComponentDraft[] }[];
  rule: RuleDraft;
}

interface Draft {
  name: string;
  label: string;
  description: string;
  layout: string;
  components: ComponentDraft[];
  actions: Record<"EDIT" | "DELETE" | "CLONE", { on: boolean; label: string; rule: RuleDraft }>;
}

const TYPE_TEXT: Record<string, string> = {
  HEADER: "Header",
  DETAILS: "Details (the layout)",
  RELATED_LIST: "Related list",
  TABS: "Tabs",
  ACTIVITY: "Activity",
  HISTORY: "History",
};
const OPERATOR_OPTIONS: { value: Operator; text: string }[] = [
  { value: "EQUALS", text: "is" },
  { value: "NOT_EQUALS", text: "is not" },
  { value: "IS_EMPTY", text: "is empty" },
  { value: "IS_NOT_EMPTY", text: "is not empty" },
];
const ACTIONS = ["EDIT", "DELETE", "CLONE"] as const;
const ACTION_TEXT: Record<string, string> = { EDIT: "Edit", DELETE: "Delete", CLONE: "Clone" };

function emptyComponent(type: PageComponent["type"], index: number): ComponentDraft {
  return {
    key: `${type.toLowerCase().replace("_", "-")}-${index + 1}`,
    type,
    region: "MAIN",
    titleField: "",
    highlightFields: [],
    label: "",
    object: "",
    field: "",
    columns: [],
    limit: "",
    tabs: type === "TABS" ? [{ label: "Tab 1", components: [] }] : [],
    rule: { kind: "none" },
  };
}

const EMPTY_ACTIONS: Draft["actions"] = {
  EDIT: { on: false, label: "", rule: { kind: "none" } },
  DELETE: { on: false, label: "", rule: { kind: "none" } },
  CLONE: { on: false, label: "", rule: { kind: "none" } },
};

const EMPTY: Draft = {
  name: "",
  label: "",
  description: "",
  layout: "",
  components: [emptyComponent("DETAILS", 0)],
  actions: EMPTY_ACTIONS,
};

function ruleDraftOf(rule: VisibilityRule | undefined): RuleDraft {
  if (!rule) {
    return { kind: "none" };
  }
  if (rule.all || rule.any) {
    return { kind: "kept", rule };
  }
  if (rule.ability) {
    return { kind: "ability", ability: rule.ability };
  }
  if (rule.field && rule.operator) {
    return { kind: "field", field: rule.field, operator: rule.operator, value: rule.value === undefined ? "" : String(rule.value) };
  }
  return { kind: "kept", rule };
}

function ruleOf(draft: RuleDraft): VisibilityRule | undefined {
  switch (draft.kind) {
    case "field":
      return draft.operator === "IS_EMPTY" || draft.operator === "IS_NOT_EMPTY"
        ? { field: draft.field, operator: draft.operator }
        : { field: draft.field, operator: draft.operator, value: draft.value };
    case "ability":
      return { ability: draft.ability };
    case "kept":
      return draft.rule;
    default:
      return undefined;
  }
}

function componentDraftOf(component: PageComponent | TabComponent, index: number): ComponentDraft {
  const page = component as PageComponent;
  return {
    ...emptyComponent(component.type, index),
    key: component.key,
    region: page.region ?? "MAIN",
    titleField: page.titleField ?? "",
    highlightFields: page.highlightFields ?? [],
    label: component.label ?? "",
    object: component.object ?? "",
    field: component.field ?? "",
    columns: component.columns ?? [],
    limit: component.limit === undefined ? "" : String(component.limit),
    tabs: (page.tabs ?? []).map((tab) => ({
      label: tab.label,
      components: tab.components.map((inner, at) => componentDraftOf(inner, at)),
    })),
    rule: ruleDraftOf(component.visibleWhen),
  };
}

function draftOf(page: RecordPageView): Draft {
  const actions = { ...EMPTY_ACTIONS };
  for (const action of page.definition.actions ?? []) {
    actions[action.action] = { on: true, label: action.label ?? "", rule: ruleDraftOf(action.visibleWhen) };
  }
  return {
    name: page.apiName,
    label: page.label,
    description: page.description,
    layout: page.definition.layout,
    components: page.definition.components.map((component, index) => componentDraftOf(component, index)),
    actions,
  };
}

function componentOf(draft: ComponentDraft, inTab: boolean): PageComponent {
  const component: PageComponent = { key: draft.key, type: draft.type };
  if (!inTab && draft.region === "SIDEBAR") {
    component.region = "SIDEBAR";
  }
  const rule = ruleOf(draft.rule);
  if (rule) {
    component.visibleWhen = rule;
  }
  if (draft.type === "HEADER") {
    if (draft.titleField) {
      component.titleField = draft.titleField;
    }
    if (draft.highlightFields.length > 0) {
      component.highlightFields = draft.highlightFields;
    }
  }
  if (draft.type === "RELATED_LIST") {
    component.object = draft.object;
    component.field = draft.field;
    component.columns = draft.columns;
    if (draft.label) {
      component.label = draft.label;
    }
    if (draft.limit) {
      component.limit = Number(draft.limit);
    }
  }
  if (draft.type === "TABS") {
    component.tabs = draft.tabs.map((tab) => ({
      label: tab.label,
      components: tab.components.map((inner) => componentOf(inner, true) as TabComponent),
    }));
  }
  return component;
}

function definitionOf(draft: Draft): RecordPageDefinition {
  const actions: PageAction[] = ACTIONS.filter((action) => draft.actions[action].on).map((action) => {
    const entry: PageAction = { action };
    if (draft.actions[action].label) {
      entry.label = draft.actions[action].label;
    }
    const rule = ruleOf(draft.actions[action].rule);
    if (rule) {
      entry.visibleWhen = rule;
    }
    return entry;
  });
  return {
    layout: draft.layout,
    components: draft.components.map((component) => componentOf(component, false)),
    ...(actions.length > 0 ? { actions } : {}),
  };
}

function showable(object: ObjectView | { fields: FieldView[] }): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

/** The rule editor of a component or an action: no rule, one condition on a field, or one ability. */
function RuleEditor({
  id,
  rule,
  fields,
  abilities,
  onChange,
}: {
  id: string;
  rule: RuleDraft;
  fields: FieldView[];
  abilities: AbilityInfo[];
  onChange: (rule: RuleDraft) => void;
}) {
  if (rule.kind === "kept") {
    return (
      <p>
        Shown when {describeRule(rule.rule)}.{" "}
        <button type="button" className="link-button" onClick={() => onChange({ kind: "none" })}>
          Remove this rule
        </button>
      </p>
    );
  }
  return (
    <div className="field">
      <label htmlFor={`${id}-rule`}>Shown when</label>
      <select
        id={`${id}-rule`}
        value={rule.kind}
        onChange={(event) => {
          const kind = event.target.value;
          if (kind === "field") {
            onChange({ kind: "field", field: fields[0]?.apiName ?? "", operator: "EQUALS", value: "" });
          } else if (kind === "ability") {
            onChange({ kind: "ability", ability: abilities[0]?.key ?? "" });
          } else {
            onChange({ kind: "none" });
          }
        }}
      >
        <option value="none">Always</option>
        <option value="field">A field has a value</option>
        <option value="ability">The person holds an ability</option>
      </select>
      {rule.kind === "field" ? (
        <>
          <label htmlFor={`${id}-rule-field`} className="visually-hidden">
            Field
          </label>
          <select
            id={`${id}-rule-field`}
            value={rule.field}
            onChange={(event) => onChange({ ...rule, field: event.target.value })}
          >
            {fields.map((field) => (
              <option key={field.apiName} value={field.apiName}>
                {field.label}
              </option>
            ))}
          </select>
          <label htmlFor={`${id}-rule-operator`} className="visually-hidden">
            Comparison
          </label>
          <select
            id={`${id}-rule-operator`}
            value={rule.operator}
            onChange={(event) => onChange({ ...rule, operator: event.target.value as Operator })}
          >
            {OPERATOR_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.text}
              </option>
            ))}
          </select>
          {rule.operator === "EQUALS" || rule.operator === "NOT_EQUALS" ? (
            <>
              <label htmlFor={`${id}-rule-value`} className="visually-hidden">
                Value
              </label>
              <input
                id={`${id}-rule-value`}
                value={rule.value}
                maxLength={255}
                onChange={(event) => onChange({ ...rule, value: event.target.value })}
              />
            </>
          ) : null}
        </>
      ) : null}
      {rule.kind === "ability" ? (
        <>
          <label htmlFor={`${id}-rule-ability`} className="visually-hidden">
            Ability
          </label>
          <select
            id={`${id}-rule-ability`}
            value={rule.ability}
            onChange={(event) => onChange({ ...rule, ability: event.target.value })}
          >
            {abilities.map((ability) => (
              <option key={ability.key} value={ability.key}>
                {ability.name}
              </option>
            ))}
          </select>
        </>
      ) : null}
    </div>
  );
}

interface Knowledge {
  fields: FieldView[];
  abilities: AbilityInfo[];
  objects: ObjectSummary[];
  /** The fields of another object, loaded when a related list names it. */
  fieldsOf: (objectApiName: string) => FieldView[];
  ensure: (objectApiName: string) => void;
}

function ComponentEditor({
  id,
  draft,
  inTab,
  know,
  onChange,
  onRemove,
}: {
  id: string;
  draft: ComponentDraft;
  inTab: boolean;
  know: Knowledge;
  onChange: (next: ComponentDraft) => void;
  onRemove: () => void;
}) {
  const relatedFields = draft.object ? know.fieldsOf(draft.object) : [];
  return (
    <fieldset className="field" data-testid="page-component">
      <legend>
        {TYPE_TEXT[draft.type]} <code>{draft.key}</code>
      </legend>
      <div className="field">
        <label htmlFor={`${id}-key`}>Key</label>
        <input
          id={`${id}-key`}
          value={draft.key}
          maxLength={40}
          onChange={(event) => onChange({ ...draft, key: event.target.value })}
        />
      </div>
      {inTab ? null : (
        <div className="field">
          <label htmlFor={`${id}-region`}>Place</label>
          <select
            id={`${id}-region`}
            value={draft.region}
            onChange={(event) => onChange({ ...draft, region: event.target.value === "SIDEBAR" ? "SIDEBAR" : "MAIN" })}
          >
            <option value="MAIN">Main part</option>
            <option value="SIDEBAR">Side panel</option>
          </select>
        </div>
      )}
      {draft.type === "HEADER" ? (
        <>
          <div className="field">
            <label htmlFor={`${id}-title`}>Title field</label>
            <select
              id={`${id}-title`}
              value={draft.titleField}
              onChange={(event) => onChange({ ...draft, titleField: event.target.value })}
            >
              <option value="">None</option>
              {know.fields.map((field) => (
                <option key={field.apiName} value={field.apiName}>
                  {field.label}
                </option>
              ))}
            </select>
          </div>
          <fieldset className="field">
            <legend>Highlighted fields (at most 6)</legend>
            {know.fields.map((field) => (
              <label key={field.apiName}>
                <input
                  type="checkbox"
                  checked={draft.highlightFields.includes(field.apiName)}
                  onChange={(event) =>
                    onChange({
                      ...draft,
                      highlightFields: event.target.checked
                        ? [...draft.highlightFields, field.apiName]
                        : draft.highlightFields.filter((name) => name !== field.apiName),
                    })
                  }
                />{" "}
                {field.label}
              </label>
            ))}
          </fieldset>
        </>
      ) : null}
      {draft.type === "RELATED_LIST" ? (
        <>
          <div className="field">
            <label htmlFor={`${id}-label`}>Heading</label>
            <input
              id={`${id}-label`}
              value={draft.label}
              maxLength={80}
              onChange={(event) => onChange({ ...draft, label: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor={`${id}-object`}>Records of</label>
            <select
              id={`${id}-object`}
              value={draft.object}
              onChange={(event) => {
                know.ensure(event.target.value);
                onChange({ ...draft, object: event.target.value, field: "", columns: [] });
              }}
            >
              <option value="">Choose an object</option>
              {know.objects.map((object) => (
                <option key={object.apiName} value={object.apiName}>
                  {object.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${id}-field`}>Linked through the field</label>
            <select
              id={`${id}-field`}
              value={draft.field}
              onChange={(event) => onChange({ ...draft, field: event.target.value })}
            >
              <option value="">Choose a lookup or master-detail field</option>
              {showable({ fields: relatedFields })
                .filter((field) => field.type === "LOOKUP" || field.type === "MASTER_DETAIL")
                .map((field) => (
                  <option key={field.apiName} value={field.apiName}>
                    {field.label} ({field.apiName})
                  </option>
                ))}
            </select>
          </div>
          <fieldset className="field">
            <legend>Columns</legend>
            {showable({ fields: relatedFields }).map((field) => (
              <label key={field.apiName}>
                <input
                  type="checkbox"
                  checked={draft.columns.includes(field.apiName)}
                  onChange={(event) =>
                    onChange({
                      ...draft,
                      columns: event.target.checked
                        ? [...draft.columns, field.apiName]
                        : draft.columns.filter((name) => name !== field.apiName),
                    })
                  }
                />{" "}
                {field.label}
              </label>
            ))}
          </fieldset>
          <div className="field">
            <label htmlFor={`${id}-limit`}>Rows shown (1 to 50)</label>
            <input
              id={`${id}-limit`}
              type="number"
              min={1}
              max={50}
              value={draft.limit}
              onChange={(event) => onChange({ ...draft, limit: event.target.value })}
            />
          </div>
        </>
      ) : null}
      {draft.type === "TABS" ? (
        <>
          {draft.tabs.map((tab, at) => (
            <fieldset className="field" key={at} data-testid="page-tab">
              <legend>Tab {at + 1}</legend>
              <div className="field">
                <label htmlFor={`${id}-tab-${at}`}>Tab label</label>
                <input
                  id={`${id}-tab-${at}`}
                  value={tab.label}
                  maxLength={40}
                  onChange={(event) =>
                    onChange({
                      ...draft,
                      tabs: draft.tabs.map((one, position) => (position === at ? { ...one, label: event.target.value } : one)),
                    })
                  }
                />
              </div>
              {tab.components.map((inner, position) => (
                <ComponentEditor
                  key={position}
                  id={`${id}-tab-${at}-${position}`}
                  draft={inner}
                  inTab
                  know={know}
                  onChange={(next) =>
                    onChange({
                      ...draft,
                      tabs: draft.tabs.map((one, p) =>
                        p === at
                          ? { ...one, components: one.components.map((other, q) => (q === position ? next : other)) }
                          : one,
                      ),
                    })
                  }
                  onRemove={() =>
                    onChange({
                      ...draft,
                      tabs: draft.tabs.map((one, p) =>
                        p === at ? { ...one, components: one.components.filter((_, q) => q !== position) } : one,
                      ),
                    })
                  }
                />
              ))}
              <div className="field">
                <label htmlFor={`${id}-tab-${at}-add`}>Add to this tab</label>
                <select
                  id={`${id}-tab-${at}-add`}
                  value=""
                  onChange={(event) => {
                    if (event.target.value) {
                      const type = event.target.value as PageComponent["type"];
                      onChange({
                        ...draft,
                        tabs: draft.tabs.map((one, p) =>
                          p === at
                            ? { ...one, components: [...one.components, emptyComponent(type, one.components.length)] }
                            : one,
                        ),
                      });
                    }
                  }}
                >
                  <option value="">Choose a component</option>
                  {(["DETAILS", "RELATED_LIST", "ACTIVITY", "HISTORY"] as const).map((type) => (
                    <option key={type} value={type}>
                      {TYPE_TEXT[type]}
                    </option>
                  ))}
                </select>
              </div>
            </fieldset>
          ))}
          <button
            type="button"
            className="link-button"
            onClick={() =>
              onChange({ ...draft, tabs: [...draft.tabs, { label: `Tab ${draft.tabs.length + 1}`, components: [] }] })
            }
          >
            Add a tab
          </button>
        </>
      ) : null}
      <RuleEditor
        id={id}
        rule={draft.rule}
        fields={know.fields}
        abilities={know.abilities}
        onChange={(rule) => onChange({ ...draft, rule })}
      />
      <button type="button" className="link-button" onClick={onRemove}>
        Take out {draft.key}
      </button>
    </fieldset>
  );
}

/**
 * The record pages of one object: the components of the page (header, details, related lists, tabs, activity, history),
 * their order and place, when each is shown (closed rules, never text that is evaluated) and the actions. They are made,
 * changed and removed here live or, when a change set is chosen, as drafts. The page shows what the API answers: that the
 * layout and the fields exist, and that a related list follows a relationship, is the API's to decide.
 */
export function RecordPagesPanel({ object, onChanged }: { object: ObjectView; onChanged: () => void }) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const [pages, setPages] = useState<RecordPageView[] | undefined>();
  const [layouts, setLayouts] = useState<LayoutView[]>([]);
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [abilities, setAbilities] = useState<AbilityInfo[]>([]);
  const [others, setOthers] = useState<Record<string, FieldView[]>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState<RecordPageView | undefined>();
  const [previewing, setPreviewing] = useState<string | undefined>();

  const ensure = useCallback(async (name: string) => {
    if (!name) {
      return;
    }
    try {
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName: name } } });
      if (data) {
        setOthers((known) => ({ ...known, [name]: data.data.fields }));
      }
    } catch {
      // The related list then offers no fields; the person can try again by choosing the object again.
    }
  }, []);

  const reload = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [list, layoutList, objectList, abilityList] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-pages", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", { params: { path } }),
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/abilities"),
      ]);
      if (!list.data || !layoutList.data) {
        const first = list.data ? layoutList : list;
        setFailure(await failureText(first.error, first.response));
        return;
      }
      setFailure(undefined);
      setPages(list.data.data);
      setLayouts(layoutList.data.data);
      setObjects(objectList.data?.data ?? []);
      setAbilities(abilityList.data?.data ?? []);
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
      <section aria-labelledby="record-pages-heading">
        <h3 id="record-pages-heading">Record pages</h3>
        <p className="hint">The records of this object belong to the platform, so it has no record pages.</p>
      </section>
    );
  }
  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="record-pages-failed">
        {failure}
      </p>
    );
  }
  if (!pages) {
    return <p aria-live="polite">Loading…</p>;
  }

  const fields = showable(object);
  const know: Knowledge = {
    fields,
    abilities,
    objects,
    fieldsOf: (name) => (name === objectApiName ? object.fields : (others[name] ?? [])),
    ensure: (name) => void ensure(name),
  };

  function reset() {
    setDraft(EMPTY);
    setEditing(undefined);
  }

  function edit(page: RecordPageView) {
    setEditing(page);
    setDraft(draftOf(page));
    for (const component of page.definition.components) {
      const related = [component, ...(component.tabs ?? []).flatMap((tab) => tab.components)];
      related.filter((one) => one.object).forEach((one) => void ensure(one.object ?? ""));
    }
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const definition = definitionOf(draft);
    void act(async () => {
      if (editing) {
        const update = { label: draft.label, description: draft.description, definition, version: editing.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_RECORD_PAGE",
              objectApiName,
              itemApiName: editing.apiName,
              updateRecordPage: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}", {
              params: { path: { objectApiName, pageApiName: editing.apiName } },
              body: update,
            });
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The record page was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = { name: draft.name, label: draft.label, description: draft.description, definition };
      const result = workingSet
        ? await addToSet(workingSet, { kind: "CREATE_RECORD_PAGE", objectApiName, createRecordPage: request })
        : await api.POST("/api/v1/metadata/objects/{objectApiName}/record-pages", {
            params: { path: { objectApiName } },
            body: request,
          });
      if (result.response.ok) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record page was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(page: RecordPageView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_RECORD_PAGE", objectApiName, itemApiName: page.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}", {
            params: { path: { objectApiName, pageApiName: page.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record page was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const formName = editing ? "Change the record page" : "Add a record page";

  return (
    <section aria-labelledby="record-pages-heading" data-testid="record-pages">
      <h3 id="record-pages-heading">Record pages</h3>
      <p className="hint">
        A record page puts the details of a layout together with a header, related lists, tabs and actions. When a part is
        shown can depend on a field or on an ability of the person.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="record-pages" />
      {pages.length === 0 ? (
        <p>No record pages yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Record page</th>
              <th>API name</th>
              <th>Layout</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {pages.map((page) => (
              <tr key={page.apiName} data-testid="record-page-row">
                <td>
                  {page.label}
                  {page.description ? <div className="hint">{page.description}</div> : null}
                </td>
                <td>
                  <code>{page.apiName}</code>
                </td>
                <td>
                  <code>{page.definition.layout}</code>
                </td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    aria-expanded={previewing === page.apiName}
                    onClick={() => {
                      const open = previewing === page.apiName;
                      setPreviewing(open ? undefined : page.apiName);
                      if (!open) {
                        page.definition.components
                          .flatMap((component) => [component, ...(component.tabs ?? []).flatMap((tab) => tab.components)])
                          .filter((component) => component.object)
                          .forEach((component) => void ensure(component.object ?? ""));
                      }
                    }}
                  >
                    Preview
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => edit(page)}>
                    Change
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(page)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {pages
        .filter((page) => page.apiName === previewing)
        .map((page) => (
          <RecordPagePreview
            key={page.apiName}
            page={page.definition}
            title={page.label}
            layouts={layouts}
            fields={object.fields}
            fieldsOf={know.fieldsOf}
          />
        ))}

      <h4>{formName}</h4>
      <form className="form" onSubmit={save} noValidate aria-label={formName}>
        {editing ? (
          <p>
            <code>{editing.apiName}</code>. The name of a record page never changes.
          </p>
        ) : (
          <div className="field">
            <label htmlFor="record-page-name">Name</label>
            <input
              id="record-page-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
              aria-describedby="record-page-name-hint"
            />
            <p className="hint" id="record-page-name-hint">
              The permanent name, starting with a capital letter, for example Overview. The platform adds the ending __c.
            </p>
          </div>
        )}
        <div className="field">
          <label htmlFor="record-page-label">Label</label>
          <input
            id="record-page-label"
            value={draft.label}
            maxLength={80}
            onChange={(event) => setDraft({ ...draft, label: event.target.value })}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="record-page-description">Description</label>
          <input
            id="record-page-description"
            value={draft.description}
            maxLength={500}
            onChange={(event) => setDraft({ ...draft, description: event.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="record-page-layout">Layout shown by the details</label>
          <select
            id="record-page-layout"
            value={draft.layout}
            onChange={(event) => setDraft({ ...draft, layout: event.target.value })}
            required
          >
            <option value="">Choose a page layout</option>
            {layouts.map((layout) => (
              <option key={layout.apiName} value={layout.apiName}>
                {layout.label}
              </option>
            ))}
          </select>
        </div>

        {draft.components.map((component, index) => (
          <ComponentEditor
            key={index}
            id={`component-${index}`}
            draft={component}
            inTab={false}
            know={know}
            onChange={(next) =>
              setDraft({ ...draft, components: draft.components.map((one, at) => (at === index ? next : one)) })
            }
            onRemove={() => setDraft({ ...draft, components: draft.components.filter((_, at) => at !== index) })}
          />
        ))}
        <div className="field">
          <label htmlFor="add-component">Add a component</label>
          <select
            id="add-component"
            value=""
            onChange={(event) => {
              if (event.target.value) {
                setDraft({
                  ...draft,
                  components: [
                    ...draft.components,
                    emptyComponent(event.target.value as PageComponent["type"], draft.components.length),
                  ],
                });
              }
            }}
          >
            <option value="">Choose a component</option>
            {Object.entries(TYPE_TEXT).map(([type, text]) => (
              <option key={type} value={type}>
                {text}
              </option>
            ))}
          </select>
        </div>

        <fieldset className="field">
          <legend>Actions</legend>
          {ACTIONS.map((action) => (
            <div className="field" key={action}>
              <label>
                <input
                  type="checkbox"
                  checked={draft.actions[action].on}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      actions: { ...draft.actions, [action]: { ...draft.actions[action], on: event.target.checked } },
                    })
                  }
                />{" "}
                {ACTION_TEXT[action]}
              </label>
              {draft.actions[action].on ? (
                <RuleEditor
                  id={`action-${action}`}
                  rule={draft.actions[action].rule}
                  fields={fields}
                  abilities={abilities}
                  onChange={(rule) =>
                    setDraft({ ...draft, actions: { ...draft.actions, [action]: { ...draft.actions[action], rule } } })
                  }
                />
              ) : null}
            </div>
          ))}
        </fieldset>

        <div>
          <button type="submit" className="button" disabled={busy}>
            {editing ? "Save the record page" : "Add the record page"}
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
