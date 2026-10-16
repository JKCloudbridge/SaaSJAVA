import type { components } from "@/lib/api/generated/schema";

type FieldView = components["schemas"]["FieldView"];
type RecordPageView = components["schemas"]["RecordPageView"];
type RecordPageDefinition = components["schemas"]["RecordPageDefinition"];
type PageComponent = components["schemas"]["PageComponent"];
type TabComponent = components["schemas"]["TabComponent"];
type PageAction = components["schemas"]["PageAction"];
type VisibilityRule = components["schemas"]["VisibilityRule"];

export type Operator = NonNullable<VisibilityRule["operator"]>;

/** The rule of a component as a form can edit it; a combined rule (all/any) is kept as it is and only shown in words. */
export type RuleDraft =
  | { kind: "none" }
  | { kind: "field"; field: string; operator: Operator; value: string }
  | { kind: "ability"; ability: string }
  | { kind: "kept"; rule: VisibilityRule };

/** A component's type, at the top level or nested in a tab (Sprint 18 added TAB to the latter only). */
export type AnyComponentType = PageComponent["type"] | TabComponent["type"];

export interface TabDraft {
  label: string;
  components: ComponentDraft[];
}

export interface ComponentDraft {
  key: string;
  type: AnyComponentType;
  region: "MAIN" | "SIDEBAR";
  titleField: string;
  highlightFields: string[];
  label: string;
  object: string;
  field: string;
  columns: string[];
  limit: string;
  /** For TAB: the tab of the organization it shows (Sprint 20). */
  tab: string;
  tabs: TabDraft[];
  rule: RuleDraft;
}

export type ActionName = "EDIT" | "DELETE" | "CLONE" | "SHARE";

export interface ActionDraft {
  on: boolean;
  label: string;
  rule: RuleDraft;
}

export interface RecordPageDraft {
  name: string;
  label: string;
  description: string;
  layout: string;
  components: ComponentDraft[];
  actions: Record<ActionName, ActionDraft>;
}

export const TYPE_TEXT: Record<string, string> = {
  HEADER: "Header",
  DETAILS: "Details (the layout)",
  RELATED_LIST: "Related list",
  TABS: "Tabs",
  ACTIVITY: "Activity",
  HISTORY: "History",
  TAB: "A tab of the organization",
};

export const OPERATOR_OPTIONS: { value: Operator; text: string }[] = [
  { value: "EQUALS", text: "is" },
  { value: "NOT_EQUALS", text: "is not" },
  { value: "IS_EMPTY", text: "is empty" },
  { value: "IS_NOT_EMPTY", text: "is not empty" },
];

export const ACTIONS: readonly ActionName[] = ["EDIT", "DELETE", "CLONE", "SHARE"];
export const ACTION_TEXT: Record<string, string> = { EDIT: "Edit", DELETE: "Delete", CLONE: "Clone", SHARE: "Share" };

export function emptyComponent(type: AnyComponentType, index: number): ComponentDraft {
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
    tab: "",
    tabs: type === "TABS" ? [{ label: "Tab 1", components: [] }] : [],
    rule: { kind: "none" },
  };
}

export function emptyActions(): RecordPageDraft["actions"] {
  return {
    EDIT: { on: false, label: "", rule: { kind: "none" } },
    DELETE: { on: false, label: "", rule: { kind: "none" } },
    CLONE: { on: false, label: "", rule: { kind: "none" } },
    SHARE: { on: false, label: "", rule: { kind: "none" } },
  };
}

export function emptyRecordPage(): RecordPageDraft {
  return {
    name: "",
    label: "",
    description: "",
    layout: "",
    components: [emptyComponent("DETAILS", 0)],
    actions: emptyActions(),
  };
}

export function ruleDraftOf(rule: VisibilityRule | undefined): RuleDraft {
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
    return {
      kind: "field",
      field: rule.field,
      operator: rule.operator,
      value: rule.value === undefined ? "" : String(rule.value),
    };
  }
  return { kind: "kept", rule };
}

export function ruleOf(draft: RuleDraft): VisibilityRule | undefined {
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

export function componentDraftOf(component: PageComponent | TabComponent, index: number): ComponentDraft {
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
    tab: (component as TabComponent).tab ?? "",
    tabs: (page.tabs ?? []).map((tab) => ({
      label: tab.label,
      components: tab.components.map((inner, at) => componentDraftOf(inner, at)),
    })),
    rule: ruleDraftOf(component.visibleWhen),
  };
}

export function recordPageDraftOf(page: RecordPageView): RecordPageDraft {
  const actions = emptyActions();
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

/**
 * Builds the wire shape for a component from its draft. Assembled as a plain record (its branches set only the
 * properties its own type allows) and cast once at the end: the server's schema is the real check, this only has to
 * produce what it expects.
 */
export function componentOf(draft: ComponentDraft, inTab: boolean): PageComponent | TabComponent {
  const component: Record<string, unknown> = { key: draft.key, type: draft.type };
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
  if (draft.type === "TAB") {
    component.tab = draft.tab;
    if (draft.label) {
      component.label = draft.label;
    }
  }
  if (draft.type === "TABS") {
    component.tabs = draft.tabs.map((tab) => ({
      label: tab.label,
      components: tab.components.map((inner) => componentOf(inner, true) as TabComponent),
    }));
  }
  return component as PageComponent | TabComponent;
}

export function recordPageDefinitionOf(draft: RecordPageDraft): RecordPageDefinition {
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
    components: draft.components.map((component) => componentOf(component, false) as PageComponent),
    ...(actions.length > 0 ? { actions } : {}),
  };
}

/** The fields a page can name: the object's own, standard and custom ones; the system fields are the platform's. */
export function showable(object: { fields: FieldView[] }): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

/** Every component of a page, the ones nested in tabs included. */
export function allComponents(components: readonly ComponentDraft[]): ComponentDraft[] {
  return components.flatMap((component) => [component, ...component.tabs.flatMap((tab) => tab.components)]);
}
