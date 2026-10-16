"use client";

import type { components } from "@/lib/api/generated/schema";
import {
  emptyComponent,
  OPERATOR_OPTIONS,
  showable,
  TYPE_TEXT,
  type AnyComponentType,
  type ComponentDraft,
  type Operator,
  type RuleDraft,
} from "@/lib/builder/recordPageDraft";
import { describeRule } from "./RecordPagePreview";

type FieldView = components["schemas"]["FieldView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type AbilityInfo = components["schemas"]["AbilityInfo"];
type TabView = components["schemas"]["TabView"];

/** The rule editor of a component or an action: no rule, one condition on a field, or one ability. */
export function RuleEditor({
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

export interface Knowledge {
  fields: FieldView[];
  abilities: AbilityInfo[];
  objects: ObjectSummary[];
  /** The tabs the organization has made (Setup, Tabs): a TAB component picks one of them. */
  tabs: TabView[];
  /** The fields of another object, loaded when a related list names it. */
  fieldsOf: (objectApiName: string) => FieldView[];
  ensure: (objectApiName: string) => void;
}

export function ComponentEditor({
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
      {draft.type === "TAB" ? (
        <>
          <div className="field">
            <label htmlFor={`${id}-label`}>Label (overrides the tab&apos;s own)</label>
            <input
              id={`${id}-label`}
              value={draft.label}
              maxLength={80}
              onChange={(event) => onChange({ ...draft, label: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor={`${id}-tab`}>Tab</label>
            <select
              id={`${id}-tab`}
              value={draft.tab}
              onChange={(event) => onChange({ ...draft, tab: event.target.value })}
            >
              <option value="">Choose a tab</option>
              {draft.tab !== "" && !know.tabs.some((tab) => tab.apiName === draft.tab) ? (
                <option value={draft.tab}>{draft.tab} (not found)</option>
              ) : null}
              {know.tabs.map((tab) => (
                <option key={tab.apiName} value={tab.apiName}>
                  {tab.label}
                </option>
              ))}
            </select>
            <p className="hint">A tab the organization made in Setup, Tabs.</p>
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
                      const type = event.target.value as AnyComponentType;
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
                  {(["DETAILS", "RELATED_LIST", "ACTIVITY", "HISTORY", "TAB"] as const).map((type) => (
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
