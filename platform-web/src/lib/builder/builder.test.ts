import { describe, expect, it } from "vitest";
import { carriesDrag, DRAG_MIME, readDrag, startDrag } from "./dragPayload";
import {
  addSection,
  emptyLayout,
  fromDefinition,
  missingRequired,
  moveSectionTo,
  nudgeField,
  nudgeSection,
  place,
  placedFields,
  removeField,
  removeSection,
  renameSection,
  sendField,
  setBehaviour,
  setColumns,
  toDefinition,
} from "./layoutModel";
import {
  changeEntry,
  entriesFromItems,
  headingEntry,
  isListed,
  itemsFromEntries,
  linkEntry,
  objectEntry,
  recordPageEntry,
  tabEntry,
  unfinished,
} from "./navigationModel";
import {
  addComponent,
  addTab,
  describeComponent,
  detailsComponent,
  moveComponent,
  moveTabTo,
  nextKey,
  nudgeComponent,
  nudgeTab,
  relatedListComponent,
  removeComponent,
  removeTab,
  renameTab,
  tabComponent,
  tabForPage,
} from "./pageTabs";
import { emptyComponent } from "./recordPageDraft";
import { insertAt, move, removeAt, updateAt } from "./reorder";
import { criteriaFrom, criteriaNode, describeCriteria, emptyCriteria } from "./sharingCriteria";

function transfer() {
  const store = new Map<string, string>();
  return {
    effectAllowed: "uninitialized",
    get types() {
      return Array.from(store.keys());
    },
    setData: (type: string, value: string) => void store.set(type, value),
    getData: (type: string) => store.get(type) ?? "",
  } as unknown as DataTransfer;
}

describe("reorder", () => {
  it("moves, inserts, removes and updates without changing the list it was given", () => {
    const list = ["a", "b", "c"];
    expect(move(list, 0, 2)).toEqual(["b", "c", "a"]);
    expect(insertAt(list, "x", 1)).toEqual(["a", "x", "b", "c"]);
    expect(insertAt(list, "x")).toEqual(["a", "b", "c", "x"]);
    expect(removeAt(list, 1)).toEqual(["a", "c"]);
    expect(updateAt(list, 2, (item) => item + "!")).toEqual(["a", "b", "c!"]);
    expect(list).toEqual(["a", "b", "c"]);
  });

  it("leaves the list as it was for a move out of range", () => {
    expect(move(["a", "b"], 0, 5)).toEqual(["a", "b"]);
    expect(move(["a", "b"], -1, 0)).toEqual(["a", "b"]);
    expect(move(["a", "b"], 1, 1)).toEqual(["a", "b"]);
  });
});

describe("drag payload", () => {
  it("round-trips a palette chip and a placed item", () => {
    const event = { dataTransfer: transfer() };
    startDrag(event, { origin: "palette", key: "field:code__c" });
    expect(carriesDrag(event)).toBe(true);
    expect(readDrag(event)).toEqual({ origin: "palette", key: "field:code__c" });

    const second = { dataTransfer: transfer() };
    startDrag(second, { origin: "canvas", group: "section-1", index: 2 });
    expect(readDrag(second)).toEqual({ origin: "canvas", group: "section-1", index: 2 });
  });

  it("ignores a drop that did not come from a builder or is not in the expected shape", () => {
    const foreign = { dataTransfer: transfer() };
    expect(carriesDrag(foreign)).toBe(false);
    expect(readDrag(foreign)).toBeUndefined();

    const bad = { dataTransfer: transfer() };
    bad.dataTransfer.setData(DRAG_MIME, "{not json");
    expect(readDrag(bad)).toBeUndefined();
    bad.dataTransfer.setData(DRAG_MIME, JSON.stringify({ origin: "canvas", group: 4, index: "x" }));
    expect(readDrag(bad)).toBeUndefined();
    expect(readDrag({ dataTransfer: null })).toBeUndefined();
  });
});

describe("layout model", () => {
  const definition = {
    sections: [
      { label: "Main", columns: 2 as const, fields: [{ field: "a__c", behaviour: "REQUIRED" as const }, { field: "b__c" }] },
      { label: "More", fields: [] },
    ],
  };

  it("turns a saved layout into a draft and back to the same layout", () => {
    const draft = fromDefinition(definition);
    expect(draft.sections.map((section) => section.id)).toHaveLength(2);
    expect(toDefinition(draft)).toEqual({
      sections: [
        { label: "Main", columns: 2, fields: [{ field: "a__c", behaviour: "REQUIRED" }, { field: "b__c", behaviour: "EDITABLE" }] },
        { label: "More", columns: 1, fields: [] },
      ],
    });
  });

  it("places a field once: placing it again moves it and keeps its behaviour", () => {
    const draft = fromDefinition(definition);
    const [main, more] = draft.sections as [(typeof draft.sections)[number], (typeof draft.sections)[number]];
    const moved = place(draft, more.id, "a__c");
    expect(toDefinition(moved).sections.map((section) => section.fields.map((entry) => entry.field))).toEqual([["b__c"], ["a__c"]]);
    expect(moved.sections[1]?.items[0]?.behaviour).toBe("REQUIRED");
    expect(placedFields(moved)).toEqual(new Set(["a__c", "b__c"]));

    const added = place(moved, main.id, "c__c", 0);
    expect(added.sections[0]?.items.map((item) => item.field)).toEqual(["c__c", "b__c"]);
  });

  it("puts a field at the place it was dropped on, also inside its own section", () => {
    expect(emptyLayout().sections).toHaveLength(1);
    const base = fromDefinition({ sections: [{ label: "S", fields: [{ field: "a" }, { field: "b" }, { field: "c" }] }] });
    const id = base.sections[0]!.id;
    // Dropping "a" before "c" (position 2 counted with "a" still in the list) lands between "b" and "c".
    expect(place(base, id, "a", 2).sections[0]?.items.map((item) => item.field)).toEqual(["b", "a", "c"]);
    expect(place(base, id, "c", 0).sections[0]?.items.map((item) => item.field)).toEqual(["c", "a", "b"]);
    expect(place(base, "nope", "a")).toBe(base);
  });

  it("removes, nudges, sends, and sets the behaviour and columns", () => {
    const draft = fromDefinition(definition);
    const [main, more] = draft.sections as [(typeof draft.sections)[number], (typeof draft.sections)[number]];
    expect(nudgeField(draft, main.id, "a__c", 1).sections[0]?.items.map((item) => item.field)).toEqual(["b__c", "a__c"]);
    expect(nudgeField(draft, main.id, "a__c", -1).sections[0]?.items.map((item) => item.field)).toEqual(["a__c", "b__c"]);
    expect(sendField(draft, "b__c", more.id).sections[1]?.items.map((item) => item.field)).toEqual(["b__c"]);
    expect(removeField(draft, "a__c").sections[0]?.items).toHaveLength(1);
    expect(setBehaviour(draft, "b__c", "HIDDEN").sections[0]?.items[1]?.behaviour).toBe("HIDDEN");
    expect(setColumns(draft, more.id, 2).sections[1]?.columns).toBe(2);
    expect(renameSection(draft, more.id, "Extra").sections[1]?.label).toBe("Extra");
  });

  it("adds, removes and orders sections", () => {
    const draft = fromDefinition(definition);
    const [main] = draft.sections as [(typeof draft.sections)[number]];
    expect(addSection(draft, "New").sections.map((section) => section.label)).toEqual(["Main", "More", "New"]);
    expect(removeSection(draft, main.id).sections.map((section) => section.label)).toEqual(["More"]);
    expect(removeSection(draft, "nope")).toBe(draft);
    expect(nudgeSection(draft, main.id, 1).sections.map((section) => section.label)).toEqual(["More", "Main"]);
    expect(moveSectionTo(draft, 1, 0).sections.map((section) => section.label)).toEqual(["More", "Main"]);
  });

  it("names the required fields the layout does not show", () => {
    const draft = fromDefinition(definition);
    expect(missingRequired(draft, ["a__c", "z__c"])).toEqual(["z__c"]);
  });
});

describe("navigation model", () => {
  it("round-trips the items, carrying only what each type takes", () => {
    const items = [
      { type: "HEADING" as const, label: "Sales" },
      { type: "OBJECT" as const, object: "Account", label: "Accounts" },
      { type: "RECORD_PAGE" as const, object: "Account", page: "Overview__c" },
      { type: "LINK" as const, path: "/members", label: "People" },
      { type: "TAB" as const, tab: "Map__c" },
    ];
    expect(itemsFromEntries(entriesFromItems(items))).toEqual(items);
  });

  it("knows what is listed and what is unfinished", () => {
    const entries = [objectEntry("Account"), tabEntry("Map__c"), headingEntry(), linkEntry(), recordPageEntry()];
    expect(isListed(entries, "OBJECT", "Account")).toBe(true);
    expect(isListed(entries, "OBJECT", "Contact")).toBe(false);
    expect(isListed(entries, "TAB", "Map__c")).toBe(true);
    expect(unfinished(entries)).toEqual([2, 3, 4]);

    const done = changeEntry(entries, 2, { label: "Sales" });
    expect(unfinished(done)).toEqual([3, 4]);
  });
});

describe("page tabs", () => {
  it("builds the components with keys the page does not use yet", () => {
    const details = detailsComponent([]);
    const again = detailsComponent([{ ...emptyComponent("TABS", 0), tabs: [{ label: "A", components: [details] }] }]);
    expect(details.key).toBe("details-1");
    expect(again.key).toBe("details-2");
    expect(nextKey([], "tab")).toBe("tab-1");

    const related = relatedListComponent([], { childObject: "Contact", field: "account__c", listLabel: "Contacts" }, ["name"]);
    expect(related).toMatchObject({ type: "RELATED_LIST", object: "Contact", field: "account__c", label: "Contacts", columns: ["name"] });
    expect(tabComponent([], "Map__c")).toMatchObject({ type: "TAB", tab: "Map__c", key: "tab-1" });
  });

  it("adds, renames, orders and removes tabs", () => {
    const tabs = addTab(addTab([]), "Second");
    expect(tabs.map((tab) => tab.label)).toEqual(["Tab 1", "Second"]);
    expect(renameTab(tabs, 0, "Main").map((tab) => tab.label)).toEqual(["Main", "Second"]);
    expect(nudgeTab(tabs, 0, 1).map((tab) => tab.label)).toEqual(["Second", "Tab 1"]);
    expect(moveTabTo(tabs, 1, 0).map((tab) => tab.label)).toEqual(["Second", "Tab 1"]);
    expect(removeTab(tabs, 0).map((tab) => tab.label)).toEqual(["Second"]);
  });

  it("moves a component inside a tab and between tabs", () => {
    const one = detailsComponent([]);
    const two = tabComponent([one], "Map__c");
    const three = tabComponent([one, two], "Chart__c");
    let tabs = addTab(addTab([]));
    tabs = addComponent(addComponent(addComponent(tabs, 0, one), 0, two), 0, three);
    expect(tabs[0]?.components.map((component) => component.key)).toEqual(["details-1", "tab-1", "tab-2"]);

    tabs = moveComponent(tabs, { tab: 0, index: 0 }, { tab: 0, at: 3 });
    expect(tabs[0]?.components.map((component) => component.key)).toEqual(["tab-1", "tab-2", "details-1"]);
    tabs = moveComponent(tabs, { tab: 0, index: 1 }, { tab: 1 });
    expect(tabs[0]?.components.map((component) => component.key)).toEqual(["tab-1", "details-1"]);
    expect(tabs[1]?.components.map((component) => component.key)).toEqual(["tab-2"]);
    tabs = nudgeComponent(tabs, 0, 1, -1);
    expect(tabs[0]?.components.map((component) => component.key)).toEqual(["details-1", "tab-1"]);
    expect(removeComponent(tabs, 0, 0)[0]?.components).toHaveLength(1);
    expect(moveComponent(tabs, { tab: 5, index: 0 }, { tab: 0 })).toEqual(tabs);
  });

  it("finds the tab that shows a custom page, or says which one to make", () => {
    const tabs = [
      { apiName: "Map__c", label: "Map", description: "", version: 1, definition: { kind: "CUSTOM" as const, customPage: "FieldMap__c" } },
      { apiName: "Chart__c", label: "Chart", description: "", version: 1, definition: { kind: "CUSTOM" as const, customPage: "Other__c" } },
    ];
    expect(tabForPage({ apiName: "FieldMap__c", label: "Field map" }, tabs)).toEqual({
      apiName: "Map__c", name: "Map", label: "Map", existing: true,
    });
    expect(tabForPage({ apiName: "Gauge__c", label: "Gauge" }, tabs)).toEqual({
      apiName: "Gauge__c", name: "Gauge", label: "Gauge", existing: false,
    });
    // The name of the page is taken by a tab of another page: the new tab gets a name of its own.
    expect(tabForPage({ apiName: "Chart__c", label: "Chart page" }, tabs)).toMatchObject({
      apiName: "ChartTab__c", name: "ChartTab", existing: false,
    });
  });

  it("describes a component for its chip", () => {
    const labelOf = (name: string) => (name === "Map__c" ? "Map" : undefined);
    expect(describeComponent(detailsComponent([]), labelOf)).toBe("Details");
    expect(describeComponent({ ...tabComponent([], "Map__c") }, labelOf)).toBe("Map");
    expect(describeComponent({ ...tabComponent([], "Gone__c") }, labelOf)).toBe("Gone__c");
    expect(describeComponent({ ...emptyComponent("RELATED_LIST", 0), label: "Contacts" }, labelOf)).toBe("Contacts");
  });
});

describe("sharing criteria", () => {
  const typeOf = (field: string) => (field === "open__c" ? "BOOLEAN" : "TEXT");

  it("reads a single condition and a flat group, and sends them back the same", () => {
    const single = { field: "stage__c", op: "EQ", values: ["Open"] };
    const group = { op: "OR", items: [single, { field: "note__c", op: "IS_EMPTY" }] };

    expect(criteriaFrom(single)).toEqual({ kind: "rows", match: "AND", rows: [{ field: "stage__c", op: "EQ", value: "Open" }] });
    expect(criteriaNode(criteriaFrom(single), typeOf)).toEqual(single);
    expect(criteriaFrom(group)).toEqual({
      kind: "rows",
      match: "OR",
      rows: [{ field: "stage__c", op: "EQ", value: "Open" }, { field: "note__c", op: "IS_EMPTY", value: "" }],
    });
    expect(criteriaNode(criteriaFrom(group), typeOf)).toEqual(group);
  });

  it("sends a checkbox value as true or false", () => {
    const node = criteriaNode({ kind: "rows", match: "AND", rows: [{ field: "open__c", op: "EQ", value: "false" }] }, typeOf);

    expect(node).toEqual({ field: "open__c", op: "EQ", values: [false] });
  });

  it("keeps a shape it cannot edit whole", () => {
    const nested = { op: "AND", items: [{ op: "OR", items: [{ field: "a", op: "EQ", values: ["x"] }] }] };
    const between = { field: "a", op: "BETWEEN", values: [1, 2] };

    expect(criteriaFrom(nested)).toEqual({ kind: "kept", node: nested });
    expect(criteriaFrom(between)).toEqual({ kind: "kept", node: between });
    expect(criteriaNode({ kind: "kept", node: nested }, typeOf)).toBe(nested);
    expect(criteriaFrom(undefined)).toEqual(emptyCriteria());
  });

  it("describes a condition in words", () => {
    const label = (field: string) => field.toUpperCase();

    expect(
      describeCriteria({ op: "AND", items: [{ field: "a", op: "EQ", values: ["x"] }, { field: "b", op: "IS_NOT_EMPTY" }] }, label),
    ).toBe("A is x and B is not empty");
  });
});
