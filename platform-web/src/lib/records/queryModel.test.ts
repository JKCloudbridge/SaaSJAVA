import { describe, expect, it } from "vitest";
import { DEAL_FORM, field } from "@/test/records";
import {
  defaultColumns,
  definitionOf,
  describeRow,
  filterNodeOf,
  filterableFields,
  newFilterRow,
  projectionOf,
  queryOfView,
  queryRequestOf,
  sortableFields,
  valuesOf,
  type FilterRow,
  type ListQuery,
} from "./queryModel";

const byName = new Map(DEAL_FORM.fields.map((one) => [one.apiName, one]));

function row(fieldName: string, op: string, text = "", text2 = ""): FilterRow {
  return { key: `k-${fieldName}-${op}`, field: fieldName, op, text, text2 };
}

describe("the columns a list starts with", () => {
  it("are what names a record first, then the fields of the layout in the order an administrator placed them", () => {
    const columns = defaultColumns(DEAL_FORM);

    expect(columns[0]).toBe("name__c");
    expect(columns.slice(0, 5)).toEqual(["name__c", "amount__c", "stage__c", "closes__c", "margin__c"]);
    expect(columns.length).toBeLessThanOrEqual(5);
  });

  it("fall back to the auto-number and the owner for an object whose layout lists nothing a person fills in", () => {
    const bare = {
      ...DEAL_FORM,
      titleField: undefined,
      layout: { source: "GENERATED", sections: [{ label: "System information", columns: 2, fields: ["sequence", "ownerId"] }] },
      fields: [field("sequence", "AUTO_NUMBER", { kind: "SYSTEM" }), field("ownerId", "LOOKUP", { kind: "SYSTEM" })],
    };

    expect(defaultColumns(bare)).toEqual(["sequence", "ownerId"]);
  });
});

describe("a filter row", () => {
  it("starts with the first comparison the server offers for the field", () => {
    expect(newFilterRow(field("a", "BOOLEAN", { filterOperators: ["EQ", "NE"] })).op).toBe("EQ");
  });

  it("is complete only when it has what its comparison needs", () => {
    const text = byName.get("name__c")!;

    expect(valuesOf(row("name__c", "EQ", ""), text)).toBeNull();
    expect(valuesOf(row("name__c", "EQ", " Acme "), text)).toEqual(["Acme"]);
    expect(valuesOf(row("name__c", "IS_EMPTY"), text)).toEqual([]);
    expect(valuesOf(row("name__c", "BETWEEN", "a", ""), text)).toBeNull();
    expect(valuesOf(row("name__c", "BETWEEN", "a", "b"), text)).toEqual(["a", "b"]);
    expect(valuesOf(row("name__c", "IN", "a, b\nc ,, "), text)).toEqual(["a", "b", "c"]);
    expect(valuesOf(row("name__c", "IN", " ,\n"), text)).toBeNull();
  });

  it("makes a checkbox's value a real yes or no, and a date and time the instant it names", () => {
    const flag = field("flag__c", "BOOLEAN");
    const moment = field("when__c", "DATETIME");

    expect(valuesOf(row("flag__c", "EQ", "true"), flag)).toEqual([true]);
    expect(valuesOf(row("flag__c", "EQ", "false"), flag)).toEqual([false]);
    expect(valuesOf(row("when__c", "GT", "2030-01-31T09:30"), moment)).toEqual(["2030-01-31T09:30:00.000Z"]);
  });
});

describe("the filter of the API", () => {
  it("is one condition alone, or all of them together, and leaves out a row that is not complete yet", () => {
    expect(filterNodeOf([row("name__c", "EQ", "")], byName)).toBeUndefined();
    expect(filterNodeOf([row("name__c", "EQ", "Acme")], byName)).toEqual({ op: "EQ", field: "name__c", values: ["Acme"] });
    expect(filterNodeOf([row("name__c", "CONTAINS", "A"), row("stage__c", "EQ", "OPEN"), row("amount__c", "GT", "")], byName)).toEqual({
      op: "AND",
      items: [
        { op: "CONTAINS", field: "name__c", values: ["A"] },
        { op: "EQ", field: "stage__c", values: ["OPEN"] },
      ],
    });
  });

  it("ignores a row about a field the member cannot see", () => {
    expect(filterNodeOf([row("secret__c", "EQ", "x")], byName)).toBeUndefined();
  });

  it("is kept as it is when it is one this screen cannot edit", () => {
    const raw = { op: "OR", items: [{ op: "EQ", field: "stage__c", values: ["OPEN"] }] };

    expect(filterNodeOf([], byName, raw)).toBe(raw);
  });
});

describe("the request of a query", () => {
  it("asks for the fields of the columns only, never the system columns that come with every record", () => {
    expect(projectionOf(["name__c", "ownerId", "createdAt", "id", "stage__c"])).toEqual(["name__c", "stage__c"]);
  });

  it("orders by sortable columns only, at most two keys, and carries the page size and the cursor", () => {
    const query: ListQuery = {
      filters: [],
      columns: ["name__c", "notes__c", "ownerId"],
      sort: [
        { field: "notes__c", descending: false },
        { field: "name__c", descending: true },
        { field: "amount__c", descending: false },
        { field: "closes__c", descending: false },
      ],
    };

    const request = queryRequestOf(query, DEAL_FORM, 50, "cursor-1");

    expect(request.sort).toEqual([
      { field: "name__c", descending: true },
      { field: "amount__c", descending: false },
    ]);
    expect(request.fields).toEqual(["name__c", "notes__c"]);
    expect(request.limit).toBe(50);
    expect(request.cursor).toBe("cursor-1");
    expect(request.filter).toBeUndefined();
  });

  it("offers only the fields the server says can be filtered and ordered by", () => {
    expect(filterableFields(DEAL_FORM).map((one) => one.apiName)).not.toContain("notes__c");
    expect(sortableFields(DEAL_FORM).map((one) => one.apiName)).not.toContain("ownerId");
  });
});

describe("a saved view", () => {
  it("round-trips: a list saved as a view comes back as the same list", () => {
    const query: ListQuery = {
      filters: [row("name__c", "CONTAINS", "Acme"), row("amount__c", "BETWEEN", "10", "20"), row("stage__c", "IN", "OPEN, WON")],
      sort: [{ field: "amount__c", descending: true }],
      columns: ["name__c", "amount__c"],
    };

    const stored = definitionOf(query, DEAL_FORM);
    const back = queryOfView(stored, DEAL_FORM);

    expect(back.columns).toEqual(["name__c", "amount__c"]);
    expect(back.sort).toEqual([{ field: "amount__c", descending: true }]);
    expect(back.filters.map(({ field: name, op, text, text2 }) => ({ name, op, text, text2 }))).toEqual([
      { name: "name__c", op: "CONTAINS", text: "Acme", text2: "" },
      { name: "amount__c", op: "BETWEEN", text: "10", text2: "20" },
      { name: "stage__c", op: "IN", text: "OPEN, WON", text2: "" },
    ]);
    expect(back.raw).toBeUndefined();
  });

  it("keeps a filter it cannot edit, and still runs it", () => {
    const definition = { filter: { op: "OR", items: [{ op: "EQ", field: "stage__c", values: ["OPEN"] }] }, columns: ["name__c"] };

    const back = queryOfView(definition, DEAL_FORM);

    expect(back.filters).toEqual([]);
    expect(back.raw).toEqual(definition.filter);
    expect(queryRequestOf(back, DEAL_FORM, 25).filter).toEqual(definition.filter);
  });

  it("drops the columns and the order keys the member cannot see, and falls back to the default columns", () => {
    const back = queryOfView({ columns: ["secret__c"], sort: [{ field: "secret__c", descending: false }] }, DEAL_FORM);

    expect(back.columns).toEqual(defaultColumns(DEAL_FORM));
    expect(back.sort).toEqual([]);
  });

  it("reads a date and time condition back as the local value of the control", () => {
    const back = queryOfView({ filter: { op: "GT", field: "createdAt", values: ["2030-01-31T09:30:00.000Z"] } }, DEAL_FORM);

    expect(back.filters[0]?.text).toBe("2030-01-31T09:30");
  });
});

describe("the words for a condition", () => {
  it("say what it asks", () => {
    const stage = byName.get("stage__c")!;

    expect(describeRow(row("stage__c", "EQ", "OPEN"), stage)).toBe("Stage is OPEN");
    expect(describeRow(row("stage__c", "IS_EMPTY"), stage)).toBe("Stage is empty");
    expect(describeRow(row("amount__c", "BETWEEN", "1", "2"), byName.get("amount__c")!)).toBe("Amount is between 1 and 2");
  });
});
