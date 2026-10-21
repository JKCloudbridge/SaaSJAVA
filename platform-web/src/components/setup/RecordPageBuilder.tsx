"use client";

import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { BuilderShell } from "@/components/builder/BuilderShell";
import { DropZone } from "@/components/builder/DropZone";
import { MoveButtons } from "@/components/builder/MoveButtons";
import { Palette, type PaletteItem } from "@/components/builder/Palette";
import { ActionMessages, failureText, useAction, type ActionResult } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { startDrag, type DragPayload } from "@/lib/builder/dragPayload";
import {
  addComponent,
  addTab,
  changeComponent,
  describeComponent,
  detailsComponent,
  moveComponent,
  moveTabTo,
  nudgeComponent,
  nudgeTab,
  relatedListComponent,
  removeComponent,
  removeTab,
  renameTab,
  tabComponent,
  tabForPage,
  type TabTarget,
} from "@/lib/builder/pageTabs";
import {
  ACTION_TEXT,
  ACTIONS,
  allComponents,
  emptyComponent,
  emptyRecordPage,
  recordPageDefinitionOf,
  recordPageDraftOf,
  showable,
  TYPE_TEXT,
  type ComponentDraft,
  type RecordPageDraft,
  type TabDraft,
} from "@/lib/builder/recordPageDraft";
import { usePublishOpenObject } from "@/lib/shell/objectContext";
import { problemText } from "./objectText";
import { ComponentEditor, RuleEditor, type Knowledge } from "./RecordPageEditors";
import { RecordPagePreview } from "./RecordPagePreview";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];
type RecordPageView = components["schemas"]["RecordPageView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type AbilityInfo = components["schemas"]["AbilityInfo"];
type TabView = components["schemas"]["TabView"];
type CustomPageView = components["schemas"]["CustomPageView"];
type RelationshipView = components["schemas"]["RelationshipView"];

const TABS_GROUP = "tabs";
const COLUMNS_ON_A_NEW_LIST = 3;

/** The tab a person is composing: the first TABS component of the page. */
function tabsIndexOf(draft: RecordPageDraft): number {
  return draft.components.findIndex((component) => component.type === "TABS");
}

/** The page, with a TABS component (empty) when it has none. */
function withTabsComponent(draft: RecordPageDraft): RecordPageDraft {
  return tabsIndexOf(draft) >= 0
    ? draft
    : { ...draft, components: [...draft.components, { ...emptyComponent("TABS", draft.components.length), tabs: [] }] };
}

/** A part to place: how to make it once the page's other keys are known, and what to call a tab made for it. */
interface Part {
  make: (page: readonly ComponentDraft[]) => ComponentDraft;
  label: string;
}

/**
 * The page with a part placed in a tab (at `at`, or at the end), or in a tab of its own when no tab is named. The TABS
 * component is made when the page has none. The part's key is chosen here, from the page as it is now, so two parts added
 * in quick succession can never share a key.
 */
function withPart(draft: RecordPageDraft, cardIndex: number | undefined, part: Part, at?: number): RecordPageDraft {
  const page = withTabsComponent(draft);
  const index = tabsIndexOf(page);
  const component = part.make(page.components);
  return {
    ...page,
    components: page.components.map((one, position) => {
      if (position !== index) {
        return one;
      }
      const inNewTab = cardIndex === undefined || cardIndex >= one.tabs.length;
      return {
        ...one,
        tabs: inNewTab
          ? addComponent(addTab(one.tabs, part.label), one.tabs.length, component)
          : addComponent(one.tabs, cardIndex, component, at),
      };
    }),
  };
}

/** The page with its tabs changed; the TABS component is made when the page has none. */
function withTabs(draft: RecordPageDraft, change: (tabs: TabDraft[]) => TabDraft[]): RecordPageDraft {
  const page = withTabsComponent(draft);
  const index = tabsIndexOf(page);
  return {
    ...page,
    components: page.components.map((one, position) => (position === index ? { ...one, tabs: change(one.tabs) } : one)),
  };
}

/**
 * The record page builder. The page's tabs are composed here by drag and drop: each tab card holds the parts of the tab
 * (the details of the layout, a list of related records, a tab the organization made). A custom page is shown through a
 * tab, so dropping a page chip uses the tab that already shows it or makes one when the page is saved. The other parts of
 * the page (header, side panel, standalone lists), the actions and every rule on when something is shown stay in the
 * forms under the canvas, because they are settings and not arrangement. Save sends one change: the record page
 * (and any tab it needs first), live or into the chosen change set.
 */
export function RecordPageBuilder() {
  const params = useParams<{ objectApiName: string; pageApiName: string }>();
  const objectApiName = params.objectApiName;
  const pageApiName = params.pageApiName;
  const creating = pageApiName === "new";
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();

  const [object, setObject] = useState<ObjectView | undefined>();
  usePublishOpenObject(objectApiName, object?.label);
  const [page, setPage] = useState<RecordPageView | undefined>();
  const [layouts, setLayouts] = useState<LayoutView[]>([]);
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [abilities, setAbilities] = useState<AbilityInfo[]>([]);
  const [tabs, setTabs] = useState<TabView[]>([]);
  const [customPages, setCustomPages] = useState<CustomPageView[]>([]);
  const [children, setChildren] = useState<RelationshipView[]>([]);
  const [others, setOthers] = useState<Record<string, FieldView[]>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<RecordPageDraft>(emptyRecordPage);
  const [pending, setPending] = useState<Record<string, TabTarget & { page: string }>>({});
  const [baseline, setBaseline] = useState("");
  const [target, setTarget] = useState(0);
  const [settingsOf, setSettingsOf] = useState<string | undefined>();
  const [previewing, setPreviewing] = useState(false);
  const fetching = useRef<Record<string, Promise<FieldView[]>>>({});

  const snapshot = useCallback(
    (what: RecordPageDraft, tabsToMake: Record<string, unknown>) =>
      JSON.stringify([what.name, what.label, what.description, recordPageDefinitionOf(what), Object.keys(tabsToMake)]),
    [],
  );

  const fieldsOfObject = useCallback((name: string): Promise<FieldView[]> => {
    const known = fetching.current[name];
    if (known) {
      return known;
    }
    const request = (async () => {
      try {
        const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}", {
          params: { path: { objectApiName: name } },
        });
        if (data) {
          setOthers((current) => ({ ...current, [name]: data.data.fields }));
          return data.data.fields;
        }
      } catch {
        // The related list then offers no columns; choosing the object again tries once more.
      }
      delete fetching.current[name];
      return [];
    })();
    fetching.current[name] = request;
    return request;
  }, []);

  const load = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [one, layoutList, objectList, abilityList, tabList, pageList, related] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", { params: { path } }),
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/abilities"),
        api.GET("/api/v1/metadata/tabs"),
        api.GET("/api/v1/metadata/custom-pages"),
        api.GET("/api/v1/metadata/objects/{objectApiName}/relationships", { params: { path } }),
      ]);
      if (!one.data || !layoutList.data) {
        const first = one.data ? layoutList : one;
        setFailure(await failureText(first.error, first.response));
        return;
      }
      setObject(one.data.data);
      setLayouts(layoutList.data.data);
      setObjects(objectList.data?.data ?? []);
      setAbilities(abilityList.data?.data ?? []);
      setTabs(tabList.data?.data ?? []);
      setCustomPages(pageList.data?.data ?? []);
      setChildren(related.data?.data.children ?? []);
      if (creating) {
        const fresh = emptyRecordPage();
        setDraft(fresh);
        setPending({});
        setBaseline(snapshot(fresh, {}));
        return;
      }
      const found = await api.GET("/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}", {
        params: { path: { objectApiName, pageApiName } },
      });
      if (!found.data) {
        setFailure(await failureText(found.error, found.response));
        return;
      }
      const view = found.data.data;
      const built = recordPageDraftOf(view);
      setPage(view);
      setDraft(built);
      setPending({});
      setBaseline(snapshot(built, {}));
      setFailure(undefined);
      allComponents(built.components)
        .filter((component) => component.object)
        .forEach((component) => void fieldsOfObject(component.object));
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName, pageApiName, creating, snapshot, fieldsOfObject]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const reload = useCallback(async () => {
    if (!creating) {
      await load();
    }
  }, [creating, load]);
  const { busy, notice, problem, act } = useAction(reload);

  const fields = useMemo(() => (object ? showable(object) : []), [object]);
  const tabLabels = useMemo(() => new Map(tabs.map((tab) => [tab.apiName, tab.label])), [tabs]);
  const labelOfTab = useCallback(
    (apiName: string) =>
      tabLabels.get(apiName) ?? Object.values(pending).find((one) => one.apiName === apiName)?.label,
    [tabLabels, pending],
  );
  const dirty = baseline !== "" && snapshot(draft, pending) !== baseline;
  const listHref = `/setup/objects/${encodeURIComponent(objectApiName)}?section=record-pages`;

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="record-page-builder-failed">
        {failure}
      </p>
    );
  }
  if (!object) {
    return <p aria-live="polite">Loading…</p>;
  }
  if (object.managedBy) {
    return <p className="hint">The records of this object belong to the platform, so it has no record pages.</p>;
  }

  const tabsAt = tabsIndexOf(draft);
  const tabsComponent = tabsAt >= 0 ? draft.components[tabsAt] : undefined;
  const cards: TabDraft[] = tabsComponent?.tabs ?? [];
  const targetCard = Math.min(target, Math.max(cards.length - 1, 0));

  const know: Knowledge = {
    fields,
    abilities,
    objects,
    tabs,
    fieldsOf: (name) => (name === objectApiName ? object.fields : (others[name] ?? [])),
    ensure: (name) => void fieldsOfObject(name),
  };

  function setCards(next: (current: TabDraft[]) => TabDraft[]) {
    setDraft((current) => {
      const at = tabsIndexOf(current);
      const component = current.components[at];
      if (!component) {
        return current;
      }
      return {
        ...current,
        components: current.components.map((one, index) => (index === at ? { ...one, tabs: next(one.tabs) } : one)),
      };
    });
  }

  /** The part a palette chip stands for. A related list reads the fields of the other object for its columns. */
  async function partFor(key: string): Promise<Part | undefined> {
    const rest = key.slice(key.indexOf(":") + 1);
    if (key === "std:details") {
      return { make: (page) => detailsComponent(page), label: "Details" };
    }
    if (key.startsWith("std:related:")) {
      const relationship = children[Number(rest.slice("related:".length))];
      if (!relationship) {
        return undefined;
      }
      const known = await fieldsOfObject(relationship.childObject);
      const columns = showable({ fields: known })
        .filter((field) => field.apiName !== relationship.field)
        .slice(0, COLUMNS_ON_A_NEW_LIST)
        .map((field) => field.apiName);
      return { make: (page) => relatedListComponent(page, relationship, columns), label: relationship.listLabel };
    }
    if (key.startsWith("tab:")) {
      return { make: (page) => tabComponent(page, rest), label: labelOfTab(rest) ?? rest };
    }
    if (key.startsWith("page:")) {
      const custom = customPages.find((one) => one.apiName === rest);
      if (!custom) {
        return undefined;
      }
      const shown = tabForPage(custom, tabs);
      if (!shown.existing) {
        setPending((current) => ({ ...current, [shown.apiName]: { ...shown, page: custom.apiName } }));
      }
      return { make: (page) => tabComponent(page, shown.apiName), label: shown.label };
    }
    return undefined;
  }

  async function addToCard(cardIndex: number | undefined, key: string, at?: number) {
    if (key === "new-tab") {
      setDraft((current) =>
        withTabs(current, (existing) => addTab(existing)),
      );
      return;
    }
    const part = await partFor(key);
    if (!part) {
      return;
    }
    setDraft((current) => withPart(current, cardIndex, part, at));
    if (cardIndex !== undefined) {
      setTarget(cardIndex);
    }
  }

  function dropOnCard(cardIndex: number, payload: DragPayload, at?: number) {
    if (payload.origin === "palette") {
      void addToCard(cardIndex, payload.key, at);
      return;
    }
    if (payload.group === TABS_GROUP) {
      setCards((current) => moveTabTo(current, payload.index, cardIndex));
      return;
    }
    const from = /^tab:(\d+)$/.exec(payload.group);
    if (from) {
      setCards((current) => moveComponent(current, { tab: Number(from[1]), index: payload.index }, { tab: cardIndex, at }));
    }
  }

  function dropOnNewTab(payload: DragPayload) {
    if (payload.origin === "palette") {
      void addToCard(undefined, payload.key);
    }
  }

  function save() {
    const neededTabs = new Set(
      allComponents(draft.components)
        .filter((component) => component.type === "TAB")
        .map((component) => component.tab),
    );
    const toMake = Object.values(pending).filter((one) => neededTabs.has(one.apiName));
    const definition = recordPageDefinitionOf(draft);
    void act(async (): Promise<ActionResult> => {
      for (const made of toMake) {
        const request = {
          name: made.name,
          label: made.label,
          description: "",
          definition: { kind: "CUSTOM" as const, customPage: made.page },
        };
        const result = workingSet
          ? await addToSet(workingSet, { kind: "CREATE_TAB", createTab: request })
          : await api.POST("/api/v1/metadata/tabs", { body: request });
        if (!result.response.ok) {
          return {
            error: result.error,
            response: result.response,
            problem: problemText(result.error, result.response),
          };
        }
        setPending((current) => {
          const { [made.apiName]: _made, ...rest } = current;
          void _made;
          return rest;
        });
      }
      if (!creating && page) {
        const update = { label: draft.label, description: draft.description, definition, version: page.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_RECORD_PAGE",
              objectApiName,
              itemApiName: page.apiName,
              updateRecordPage: update,
            })
          : await api.PUT("/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}", {
              params: { path: { objectApiName, pageApiName: page.apiName } },
              body: update,
            });
        if (result.response.ok && workingSet) {
          setBaseline(snapshot(draft, {}));
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The record page was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = { name: draft.name, label: draft.label, description: draft.description, definition };
      if (workingSet) {
        const drafted = await addToSet(workingSet, { kind: "CREATE_RECORD_PAGE", objectApiName, createRecordPage: request });
        if (drafted.response.ok) {
          setBaseline(snapshot(draft, {}));
          router.replace(listHref);
        }
        return {
          error: drafted.error,
          response: drafted.response,
          text: DRAFTED_TEXT,
          problem: problemText(drafted.error, drafted.response),
        };
      }
      const result = await api.POST("/api/v1/metadata/objects/{objectApiName}/record-pages", {
        params: { path: { objectApiName } },
        body: request,
      });
      if (result.response.ok && result.data) {
        setBaseline(snapshot(draft, {}));
        router.replace(
          `/setup/objects/${encodeURIComponent(objectApiName)}/record-pages/${encodeURIComponent(result.data.data.apiName)}`,
        );
      }
      return {
        error: result.error,
        response: result.response,
        text: "The record page was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const tabPalette: PaletteItem[] = [
    { key: "new-tab", label: "+ New tab", emphasis: true, title: "Adds an empty tab to the page" },
    ...tabs.map((tab) => ({
      key: `tab:${tab.apiName}`,
      label: tab.label,
      title: `${tab.label} (${tab.apiName})`,
    })),
  ];
  const pagePalette: PaletteItem[] = customPages.map((custom) => {
    const shown = tabForPage(custom, tabs);
    return {
      key: `page:${custom.apiName}`,
      label: custom.label,
      title: shown.existing
        ? `${custom.label}: shown through the tab ${shown.label}`
        : `${custom.label}: a tab is made for it when you save`,
    };
  });
  const standardPalette: PaletteItem[] = [
    { key: "std:details", label: "Details", title: "The details of the layout" },
    ...children.map((relationship, index) => ({
      key: `std:related:${index}`,
      label: relationship.listLabel,
      title: `${relationship.listLabel}: ${relationship.childObject} records linked through ${relationship.field}`,
    })),
  ];

  const otherAt = draft.components.map((_, index) => index).filter((index) => index !== tabsAt);
  const identityValid = draft.layout !== "";

  return (
    <BuilderShell
      testId="record-page-builder"
      crumbs={[
        { label: "Object settings", href: "/setup/objects" },
        { label: object.label, href: `/setup/objects/${encodeURIComponent(objectApiName)}?section=fields` },
        { label: "Record pages", href: listHref },
        { label: creating ? "New record page" : (draft.label || pageApiName) },
      ]}
      title={creating ? "New record page" : draft.label || pageApiName}
      backHref={listHref}
      dirty={dirty}
      busy={busy || !identityValid}
      saveLabel={creating ? "Add the record page" : "Save the record page"}
      onSave={save}
      actions={
        <button
          type="button"
          className="button button-secondary"
          aria-pressed={previewing}
          onClick={() => setPreviewing(!previewing)}
        >
          {previewing ? "Hide the preview" : "Preview"}
        </button>
      }
    >
      <ActionMessages notice={notice} problem={problem} testId="record-page-builder" />
      <section aria-labelledby="page-identity-heading" className="card">
        <h3 id="page-identity-heading">About this page</h3>
        <div className="record-grid record-grid-two">
          {creating ? (
            <div className="field">
              <label htmlFor="record-page-name">Name</label>
              <input
                id="record-page-name"
                value={draft.name}
                maxLength={40}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                aria-describedby="record-page-name-hint"
              />
              <p className="hint" id="record-page-name-hint">
                The permanent name, starting with a capital letter, for example Overview. The platform adds the ending __c.
              </p>
            </div>
          ) : (
            <p>
              <code>{page?.apiName}</code>. The name of a record page never changes.
            </p>
          )}
          <div className="field">
            <label htmlFor="record-page-label">Label</label>
            <input
              id="record-page-label"
              value={draft.label}
              maxLength={80}
              onChange={(event) => setDraft({ ...draft, label: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="record-page-layout">Layout shown by the details</label>
            <select
              id="record-page-layout"
              value={draft.layout}
              onChange={(event) => setDraft({ ...draft, layout: event.target.value })}
            >
              <option value="">Choose a page layout</option>
              {layouts.map((layout) => (
                <option key={layout.apiName} value={layout.apiName}>
                  {layout.label}
                </option>
              ))}
            </select>
            {identityValid ? null : <p className="hint">Choose a layout to be able to save the page.</p>}
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
        </div>
      </section>

      <section aria-labelledby="page-tabs-heading" className="stack">
        <h3 id="page-tabs-heading">Tabs</h3>
        <p className="hint">
          Drag parts into the tabs of the page. A page with no tabs shows its details only. A custom page is shown through a
          tab: the page chips use the tab that already shows the page, or make one when you save.
        </p>
        <div className="builder">
          <Palette
            label="Tabs and parts"
            categories={[
              { id: "standard", label: "Standard", items: standardPalette, empty: "Nothing to add." },
              { id: "tabs", label: "Tabs", items: tabPalette, empty: "The organization has made no tab yet." },
              { id: "pages", label: "Custom pages", items: pagePalette, empty: "The organization has no custom page yet." },
            ]}
            onPick={(item) => void addToCard(item.key === "new-tab" ? undefined : cards.length > 0 ? targetCard : undefined, item.key)}
          />
          <div className="canvas" data-testid="record-page-canvas">
            {cards.map((card, cardIndex) => (
              <DropZone
                key={cardIndex}
                as="section"
                className={["canvas-card", cardIndex === targetCard ? "is-target" : ""].filter(Boolean).join(" ")}
                aria-label={`Tab ${card.label || cardIndex + 1}`}
                data-testid="page-tab-card"
                onDropPayload={(payload: DragPayload) => dropOnCard(cardIndex, payload)}
              >
                <div className="canvas-card-head" onClick={() => setTarget(cardIndex)}>
                  <span
                    className="drag-handle"
                    draggable
                    title="Drag to reorder the tab"
                    aria-hidden="true"
                    onDragStart={(event) => startDrag(event, { origin: "canvas", group: TABS_GROUP, index: cardIndex })}
                  >
                    ⠿
                  </span>
                  <input
                    className="canvas-title"
                    aria-label={`Label of tab ${cardIndex + 1}`}
                    value={card.label}
                    maxLength={40}
                    onFocus={() => setTarget(cardIndex)}
                    onChange={(event) => setCards((current) => renameTab(current, cardIndex, event.target.value))}
                  />
                  {cardIndex === targetCard ? <span className="badge badge-on">Adds go here</span> : null}
                  <MoveButtons
                    name={`tab ${card.label || cardIndex + 1}`}
                    onUp={cardIndex > 0 ? () => setCards((current) => nudgeTab(current, cardIndex, -1)) : undefined}
                    onDown={
                      cardIndex < cards.length - 1 ? () => setCards((current) => nudgeTab(current, cardIndex, 1)) : undefined
                    }
                    onRemove={() => setCards((current) => removeTab(current, cardIndex))}
                  />
                </div>
                <DropZone
                  className="canvas-items is-list"
                  data-testid="page-tab-items"
                  onDropPayload={(payload: DragPayload) => dropOnCard(cardIndex, payload)}
                >
                  {card.components.length === 0 ? (
                    <p className="canvas-empty">Drag parts here, or choose one in the palette.</p>
                  ) : null}
                  {card.components.map((component, at) => {
                    const settingsKey = `${cardIndex}:${at}`;
                    return (
                      <DropZone
                        key={`${component.key}`}
                        className="stack"
                        data-testid="page-tab-part"
                        onDropPayload={(payload: DragPayload) => dropOnCard(cardIndex, payload, at)}
                      >
                        <div className="item-chip item-chip-wide">
                          <span
                            className="drag-handle"
                            draggable
                            title="Drag to move the part"
                            aria-hidden="true"
                            onDragStart={(event) =>
                              startDrag(event, { origin: "canvas", group: `tab:${cardIndex}`, index: at })
                            }
                          >
                            ⠿
                          </span>
                          <span className="entry-fields">
                            <strong>{describeComponent(component, labelOfTab)}</strong>
                            <span className="badge">{TYPE_TEXT[component.type] ?? component.type}</span>
                            {component.type === "TAB" && pending[component.tab] ? (
                              <span className="badge badge-on">Made when saved</span>
                            ) : null}
                          </span>
                          <button
                            type="button"
                            className="link-button"
                            aria-expanded={settingsOf === settingsKey}
                            onClick={() => setSettingsOf(settingsOf === settingsKey ? undefined : settingsKey)}
                          >
                            Settings
                          </button>
                          <MoveButtons
                            name={describeComponent(component, labelOfTab)}
                            upLabel="earlier"
                            downLabel="later"
                            onUp={at > 0 ? () => setCards((current) => nudgeComponent(current, cardIndex, at, -1)) : undefined}
                            onDown={
                              at < card.components.length - 1
                                ? () => setCards((current) => nudgeComponent(current, cardIndex, at, 1))
                                : undefined
                            }
                            onRemove={() => setCards((current) => removeComponent(current, cardIndex, at))}
                          />
                        </div>
                        {settingsOf === settingsKey ? (
                          <ComponentEditor
                            id={`part-${cardIndex}-${at}`}
                            draft={component}
                            inTab
                            know={know}
                            onChange={(next) => setCards((current) => changeComponent(current, cardIndex, at, next))}
                            onRemove={() => setCards((current) => removeComponent(current, cardIndex, at))}
                          />
                        ) : null}
                      </DropZone>
                    );
                  })}
                </DropZone>
              </DropZone>
            ))}
            <DropZone
              as="button"
              type="button"
              className="add-dashed"
              onClick={() => void addToCard(undefined, "new-tab")}
              onDropPayload={dropOnNewTab}
            >
              + Add a tab (or drop a part here to start one)
            </DropZone>
          </div>
        </div>
      </section>

      <section aria-labelledby="page-other-heading" className="stack">
        <h3 id="page-other-heading">Other parts of the page</h3>
        <p className="hint">
          The header, the side panel, lists outside the tabs, and when each part is shown. The tabs above are the arranged
          part.
        </p>
        {otherAt.map((index) => {
          const component = draft.components[index] as ComponentDraft;
          return (
            <ComponentEditor
              key={component.key + index}
              id={`component-${index}`}
              draft={component}
              inTab={false}
              know={know}
              onChange={(next) =>
                setDraft({ ...draft, components: draft.components.map((one, at) => (at === index ? next : one)) })
              }
              onRemove={() => setDraft({ ...draft, components: draft.components.filter((_, at) => at !== index) })}
            />
          );
        })}
        <div className="field">
          <label htmlFor="add-component">Add a part</label>
          <select
            id="add-component"
            value=""
            onChange={(event) => {
              if (event.target.value) {
                setDraft({
                  ...draft,
                  components: [
                    ...draft.components,
                    emptyComponent(
                      event.target.value as ComponentDraft["type"],
                      draft.components.length,
                    ),
                  ],
                });
              }
            }}
          >
            <option value="">Choose a part</option>
            {Object.entries(TYPE_TEXT)
              // A tab is placed in the tabs above; TABS is the canvas itself.
              .filter(([type]) => type !== "TAB" && !(type === "TABS" && tabsAt >= 0))
              .map(([type, text]) => (
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
      </section>

      {previewing ? (
        <RecordPagePreview
          page={recordPageDefinitionOf(draft)}
          title={draft.label || "Record page"}
          layouts={layouts}
          fields={object.fields}
          fieldsOf={know.fieldsOf}
        />
      ) : null}
    </BuilderShell>
  );
}
