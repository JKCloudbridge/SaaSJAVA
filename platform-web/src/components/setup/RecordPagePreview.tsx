"use client";

import type { ReactNode } from "react";
import type { components } from "@/lib/api/generated/schema";
import { LayoutPreview, placeholderOf } from "./LayoutPreview";
import type { FieldView } from "./objectText";

type RecordPageDefinition = components["schemas"]["RecordPageDefinition"];
type PageComponent = components["schemas"]["PageComponent"];
type TabComponent = components["schemas"]["TabComponent"];
type VisibilityRule = components["schemas"]["VisibilityRule"];
type LayoutView = components["schemas"]["LayoutView"];

const OPERATOR_TEXT: Record<string, string> = {
  EQUALS: "is",
  NOT_EQUALS: "is not",
  IS_EMPTY: "is empty",
  IS_NOT_EMPTY: "is not empty",
};

/** A visibility rule in words, for the preview and the forms. The rule is closed data; this only reads it aloud. */
export function describeRule(rule: VisibilityRule | undefined): string {
  if (!rule) {
    return "";
  }
  if (rule.ability) {
    return `the person holds the ability ${rule.ability}`;
  }
  if (rule.field) {
    const operator = OPERATOR_TEXT[rule.operator ?? ""] ?? rule.operator ?? "";
    return rule.value === undefined ? `${rule.field} ${operator}` : `${rule.field} ${operator} ${String(rule.value)}`;
  }
  const group = rule.all ?? rule.any ?? [];
  const joiner = rule.all ? " and " : " or ";
  return `(${group.map((inner) => describeRule(inner as VisibilityRule)).join(joiner)})`;
}

/**
 * The rendering proof of concept for a record page: the components in the order the page lists them, drawn as the
 * structure they will have, with sample values and without any record. A component with a visibility rule is drawn and
 * labelled with the rule, because without a record the preview cannot say whether it would be shown. What a person is
 * allowed to see is decided by the server.
 */
export function RecordPagePreview({
  page,
  title,
  layouts,
  fields,
  fieldsOf,
}: {
  page: RecordPageDefinition;
  title: string;
  layouts: LayoutView[];
  fields: FieldView[];
  /** The fields of another object (for the columns of a related list), or none when they are not known. */
  fieldsOf: (objectApiName: string) => FieldView[];
}) {
  const layout = layouts.find((one) => one.apiName === page.layout);
  const byName = new Map(fields.map((field) => [field.apiName, field]));

  function related(component: PageComponent | TabComponent) {
    const columns = component.columns ?? [];
    const labels = new Map(fieldsOf(component.object ?? "").map((field) => [field.apiName, field]));
    return (
      <table className="table" aria-label={component.label ?? `Related ${component.object ?? ""}`}>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column}>{labels.get(column)?.label ?? column}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            {columns.map((column) => (
              <td key={column}>{placeholderOf(labels.get(column))}</td>
            ))}
          </tr>
        </tbody>
      </table>
    );
  }

  function body(component: PageComponent | TabComponent) {
    switch (component.type) {
      case "HEADER": {
        const header = component as PageComponent;
        return (
          <div>
            <strong>{placeholderOf(byName.get(header.titleField ?? ""))}</strong>
            <div className="hint">
              {(header.highlightFields ?? [])
                .map((name) => `${byName.get(name)?.label ?? name}: ${placeholderOf(byName.get(name))}`)
                .join(" · ")}
            </div>
          </div>
        );
      }
      case "DETAILS":
        return layout ? (
          <LayoutPreview layout={layout.definition} fields={fields} title={layout.label} />
        ) : (
          <p className="hint">The layout {page.layout} is not available.</p>
        );
      case "RELATED_LIST":
        return related(component);
      case "TABS": {
        const tabs = (component as PageComponent).tabs ?? [];
        return (
          <div>
            <ul aria-label="Tabs">
              {tabs.map((tab) => (
                <li key={tab.label}>{tab.label}</li>
              ))}
            </ul>
            {tabs[0]?.components.map((inner) => (
              <Block key={inner.key} component={inner} render={body} />
            ))}
          </div>
        );
      }
      default:
        return <p className="hint">Sample {component.type === "ACTIVITY" ? "activity" : "history"} entries.</p>;
    }
  }

  return (
    <div className="preview" role="group" aria-label={`Preview of ${title}`} data-testid="record-page-preview">
      <p className="hint">Preview with sample values. No records exist yet, so nothing here is real data.</p>
      {page.components.map((component) => (
        <Block key={component.key} component={component} render={body} />
      ))}
      {(page.actions ?? []).length > 0 ? (
        <p data-testid="preview-actions">
          {(page.actions ?? []).map((action) => (
            <button type="button" className="link-button" key={action.action} disabled>
              {action.label ?? action.action}
            </button>
          ))}
        </p>
      ) : null}
    </div>
  );
}

function Block({
  component,
  render,
}: {
  component: PageComponent | TabComponent;
  render: (component: PageComponent | TabComponent) => ReactNode;
}) {
  const side = (component as PageComponent).region === "SIDEBAR";
  return (
    <section aria-label={component.key} data-testid="preview-component" data-type={component.type}>
      <h5>
        {component.type} <code>{component.key}</code>
        {side ? <span className="hint"> (side panel)</span> : null}
      </h5>
      {component.visibleWhen ? (
        <p className="hint">Shown when {describeRule(component.visibleWhen)}.</p>
      ) : null}
      {render(component)}
    </section>
  );
}
