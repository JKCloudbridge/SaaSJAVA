import type { components } from "@/lib/api/generated/schema";

export type Catalogue = components["schemas"]["WorkflowCatalogueView"];
export type WorkflowView = components["schemas"]["WorkflowView"];
export type Execution = components["schemas"]["WorkflowExecutionView"];

/**
 * The catalogue the API gives, as a stand-in for a test. It is the only place a test names an action type or a trigger kind:
 * the screens must draw whatever it holds, so a test can change it (a type added, a limit moved) and see the screen follow.
 */
export const CATALOGUE: Catalogue = {
  actionTypes: [
    {
      type: "SET_FIELDS",
      summary: "Sets fields of the record that started the workflow.",
      assignments: "TRIGGERING_OBJECT",
      settings: [],
    },
    {
      type: "MAKE_RECORD",
      summary: "Makes a record of an object.",
      assignments: "NAMED_OBJECT",
      assignedObject: "target",
      settings: [
        { key: "target", required: true, help: "The object to make a record of.", allowed: [], refersTo: "OBJECT" },
        { key: "linkField", required: false, help: "A link back to the record.", allowed: [], refersTo: "ASSIGNED_FIELD" },
      ],
    },
    {
      type: "TELL_SOMEONE",
      summary: "Mails the person the record names.",
      assignments: "NONE",
      settings: [
        { key: "who", required: true, help: "The field that names the person.", allowed: [], refersTo: "FIELD" },
        { key: "which", required: true, help: "Which mail.", allowed: ["PLAIN_NOTICE"], refersTo: "NONE" },
      ],
    },
    {
      type: "SIGNAL",
      summary: "Announces an event.",
      assignments: "NONE",
      settings: [{ key: "event", required: true, help: "The name of the event.", allowed: [], refersTo: "NONE" }],
    },
  ],
  triggerKinds: [
    { kind: "WHEN_MADE", summary: "A record is made.", scheduled: false, properties: [] },
    { kind: "WHEN_FIELD_MOVES", summary: "A chosen field changes.", scheduled: false, properties: ["fields"] },
    {
      kind: "EVERY_MORNING",
      summary: "Every morning, for each record that meets the selection.",
      scheduled: true,
      properties: ["timeZone", "time", "selection"],
    },
  ],
  limits: { maxActionsPerWorkflow: 7, maxAssignmentsPerAction: 9, maxLiteralLength: 321, maxAttempts: 4, maxChainDepth: 3 },
};

export function field(apiName: string, type = "TEXT", extra: Record<string, unknown> = {}) {
  return {
    apiName,
    label: apiName.replace("__c", ""),
    description: "",
    kind: "CUSTOM",
    type,
    required: false,
    unique: false,
    settings: {},
    retired: false,
    editable: true,
    version: 1,
    ...extra,
  };
}

export const ORDER_FIELDS = [
  field("id", "TEXT", { kind: "SYSTEM" }),
  field("status__c"),
  field("note__c"),
  field("quantity__c", "NUMBER"),
  field("total__c", "FORMULA"),
  field("old__c", "TEXT", { retired: true }),
];

export const FIELD_TYPES = [
  { type: "TEXT", label: "Text", description: "", calculated: false, allowsDefault: true, allowsRequired: true, allowsUnique: true, formulaResult: true, settings: [] },
  { type: "NUMBER", label: "Number", description: "", calculated: false, allowsDefault: true, allowsRequired: true, allowsUnique: true, formulaResult: true, settings: [] },
  { type: "FORMULA", label: "Formula", description: "", calculated: true, allowsDefault: false, allowsRequired: false, allowsUnique: false, formulaResult: false, settings: [] },
];

export const ORDER = {
  apiName: "Order__c",
  label: "Order",
  pluralLabel: "Orders",
  description: "",
  kind: "CUSTOM",
  editable: true,
  extensible: true,
  version: 1,
  fields: ORDER_FIELDS,
};

export const WATCH: WorkflowView = {
  apiName: "Watch__c",
  objectApiName: "Order__c",
  label: "Watch",
  description: "Keeps an eye",
  active: true,
  definitionVersion: 2,
  version: 7,
  trigger: { kind: "WHEN_FIELD_MOVES", fields: ["status__c"] },
  condition: "quantity__c > 1",
  actions: [
    {
      type: "SET_FIELDS",
      name: "Mark",
      settings: {},
      assignments: [
        { field: "note__c", value: "seen" },
        { field: "status__c", fromField: "note__c" },
      ],
    },
    { type: "SIGNAL", name: "Tell", settings: { event: "order.seen" } },
  ],
};

export function run(id: string, extra: Partial<Execution> = {}): Execution {
  return {
    id,
    workflow: "Watch__c",
    objectApiName: "Order__c",
    workflowVersion: 2,
    trigger: "WHEN_FIELD_MOVES",
    status: "COMPLETED",
    recordId: "0198a1b2-0000-7000-8000-0000000000aa",
    attempts: 1,
    queuedAt: "2026-10-17T09:00:00Z",
    startedAt: "2026-10-17T09:00:01Z",
    finishedAt: "2026-10-17T09:00:02Z",
    replayGeneration: 0,
    chainDepth: 0,
    resumeStep: 1,
    runAt: "2026-10-17T09:00:00Z",
    ...extra,
  } as Execution;
}

export function deadRun(id: string, extra: Partial<Execution> = {}): Execution {
  return run(id, {
    status: "DEAD_LETTERED",
    reason: "unique_clash",
    failureType: "BackgroundWriteException",
    steps: [
      { position: 1, name: "Mark", type: "SET_FIELDS", status: "COMPLETED", startedAt: "2026-10-17T09:00:01Z", attempts: 1 },
      {
        position: 2,
        name: "Tell",
        type: "SIGNAL",
        status: "FAILED",
        startedAt: "2026-10-17T09:00:01Z",
        attempts: 1,
        reason: "unique_clash",
        failureType: "BackgroundWriteException",
      },
    ],
    ...extra,
  });
}
