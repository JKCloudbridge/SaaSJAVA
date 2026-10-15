import type { FormField, ListView, ObjectForm, RecordView } from "@/lib/records/types";

/** Comparisons the engine takes, by kind of value (the lists the server publishes per field). */
export const TEXT_OPS = ["EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN", "IS_EMPTY", "IS_NOT_EMPTY", "STARTS_WITH", "CONTAINS"];
export const ORDERED_OPS = ["EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN", "IS_EMPTY", "IS_NOT_EMPTY"];
export const BOOLEAN_OPS = ["EQ", "NE", "IS_EMPTY", "IS_NOT_EMPTY"];
export const LINK_OPS = ["EQ", "NE", "IN", "IS_EMPTY", "IS_NOT_EMPTY"];

/** A field as the form description gives it, with the defaults of a plain custom field a member may type into. */
export function field(apiName: string, type: string, extra: Partial<FormField> = {}): FormField {
  return {
    apiName,
    label: apiName.replace(/__c$/, "").replace(/^./, (first) => first.toUpperCase()),
    description: "",
    kind: "CUSTOM",
    type,
    required: false,
    unique: false,
    editable: true,
    calculated: false,
    targetOpenable: false,
    searchable: false,
    externalId: false,
    sortable: true,
    filterOperators: TEXT_OPS,
    ...extra,
  };
}

/** One field of every one of the 19 types, for the registry tests. */
export function everyType(): FormField[] {
  return [
    field("text__c", "TEXT", { maxLength: 10 }),
    field("long__c", "LONG_TEXT", { maxLength: 1000, filterOperators: [], sortable: false }),
    field("whole__c", "NUMBER", { digits: 5, filterOperators: ORDERED_OPS }),
    field("dec__c", "DECIMAL", { precision: 8, scale: 2, filterOperators: ORDERED_OPS }),
    field("money__c", "CURRENCY", { precision: 10, scale: 2, filterOperators: ORDERED_OPS }),
    field("pct__c", "PERCENT", { precision: 5, scale: 1, filterOperators: ORDERED_OPS }),
    field("flag__c", "BOOLEAN", { filterOperators: BOOLEAN_OPS }),
    field("day__c", "DATE", { filterOperators: ORDERED_OPS }),
    field("moment__c", "DATETIME", { filterOperators: ORDERED_OPS }),
    field("clock__c", "TIME", { filterOperators: [], sortable: false }),
    field("mail__c", "EMAIL"),
    field("phone__c", "PHONE"),
    field("site__c", "URL"),
    field("stage__c", "PICKLIST", {
      values: [
        { value: "OPEN", label: "Open", active: true },
        { value: "WON", label: "Won", active: true },
        { value: "OLD", label: "Old", active: false },
      ],
    }),
    field("tags__c", "MULTI_PICKLIST", {
      values: [
        { value: "A", label: "Alpha", active: true },
        { value: "B", label: "Beta", active: true },
      ],
      filterOperators: [],
      sortable: false,
    }),
    field("account__c", "LOOKUP", {
      targetObject: "Account__c",
      targetLabel: "Account",
      targetOpenable: true,
      filterOperators: LINK_OPS,
      sortable: false,
    }),
    field("master__c", "MASTER_DETAIL", {
      targetObject: "Account__c",
      targetLabel: "Account",
      targetOpenable: true,
      required: true,
      filterOperators: LINK_OPS,
      sortable: false,
    }),
    field("calc__c", "FORMULA", { calculated: true, editable: false, resultType: "DECIMAL", scale: 2, filterOperators: ORDERED_OPS }),
    field("auto__c", "AUTO_NUMBER", { calculated: true, editable: false, prefix: "D-", width: 4 }),
  ];
}

const SYSTEM = (apiName: string, label: string, type: string, extra: Partial<FormField> = {}) =>
  field(apiName, type, { label, kind: "SYSTEM", editable: false, ...extra });

/** The object most record tests use: Deals, with a title, money, a picklist, a day, a link to a user and a note. */
export const DEAL_FORM: ObjectForm = {
  objectApiName: "Deal__c",
  label: "Deal",
  pluralLabel: "Deals",
  titleField: "name__c",
  actions: { create: true, update: true, delete: true },
  recordTypes: [],
  searchEnabled: true,
  fields: [
    field("name__c", "TEXT", { label: "Name", required: true, searchable: true, maxLength: 40 }),
    field("amount__c", "CURRENCY", { label: "Amount", precision: 10, scale: 2, filterOperators: ORDERED_OPS }),
    field("stage__c", "PICKLIST", {
      label: "Stage",
      values: [
        { value: "OPEN", label: "Open", active: true },
        { value: "WON", label: "Won", active: true },
      ],
    }),
    field("closes__c", "DATE", { label: "Closes", filterOperators: ORDERED_OPS }),
    field("notes__c", "LONG_TEXT", { label: "Notes", maxLength: 1000, filterOperators: [], sortable: false }),
    field("margin__c", "FORMULA", {
      label: "Margin",
      calculated: true,
      editable: false,
      resultType: "DECIMAL",
      scale: 2,
      filterOperators: ORDERED_OPS,
    }),
    SYSTEM("ownerId", "Owner", "LOOKUP", {
      required: true,
      targetObject: "User",
      targetLabel: "User",
      filterOperators: LINK_OPS,
      sortable: false,
    }),
    SYSTEM("createdAt", "Created at", "DATETIME", { filterOperators: ORDERED_OPS }),
  ],
  layout: {
    source: "LAYOUT",
    layout: "Deal_layout__c",
    sections: [
      { label: "Deal", columns: 2, fields: ["name__c", "amount__c", "stage__c", "closes__c", "margin__c"] },
      { label: "More", columns: 1, fields: ["notes__c", "ownerId", "createdAt"] },
    ],
  },
};

/** A record of the deal object as the API sends it. */
export function deal(id: string, values: Record<string, unknown> = {}, extra: Partial<RecordView> = {}): RecordView {
  return {
    id,
    objectApiName: "Deal__c",
    version: 3,
    ownerId: "11111111-1111-1111-1111-111111111111",
    fields: { name__c: `Deal ${id}`, amount__c: 1500.5, stage__c: "OPEN", ...values },
    createdAt: "2030-01-02T03:04:05Z",
    updatedAt: "2030-01-03T03:04:05Z",
    ...extra,
  };
}

/** A saved view as the API lists it. */
export function view(id: string, label: string, extra: Partial<ListView> = {}): ListView {
  return {
    id,
    objectApiName: "Deal__c",
    label,
    scope: "PERSONAL",
    mine: true,
    definition: { columns: ["name__c", "stage__c"], sort: [], filter: undefined },
    version: 0,
    updatedAt: "2030-01-03T03:04:05Z",
    ...extra,
  };
}

/** The envelope of a page of a query: records, whether another follows and the cursor for it. */
export function page(records: RecordView[], next?: string) {
  return {
    data: records.map((record) => ({ record, linked: {} })),
    pagination: { limit: 25, hasMore: next !== undefined, ...(next !== undefined ? { nextCursor: next } : {}) },
  };
}
