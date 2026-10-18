import type { components } from "@/lib/api/generated/schema";

type Catalogue = components["schemas"]["ApprovalCatalogueView"];
type Actions = components["schemas"]["ApprovalActionsView"];
type Summary = components["schemas"]["ApprovalInstanceSummaryView"];
type Task = components["schemas"]["ApprovalTaskView"];
type Process = components["schemas"]["ApprovalProcessView"];

/** A stand-in for what the API lists as the parts of a process: the screens read every list of it from the answer. */
export const CATALOGUE: Catalogue = {
  approverKinds: [
    { kind: "USER", summary: "A person", valueRefersTo: "USER" },
    { kind: "GROUP", summary: "A public group", valueRefersTo: "GROUP" },
    { kind: "OWNER", summary: "The owner of the record", valueRefersTo: "NOTHING" },
    { kind: "RECORD_USER", summary: "A person named in a field", valueRefersTo: "USER_FIELD" },
  ],
  completions: [
    { code: "ANY", summary: "Any one approver decides" },
    { code: "ALL", summary: "Every approver must approve" },
  ],
  outcomes: [
    { code: "APPROVED", summary: "When approved" },
    { code: "REJECTED", summary: "When rejected" },
    { code: "RECALLED", summary: "When withdrawn" },
  ],
  states: [
    { code: "PENDING", summary: "Waiting" },
    { code: "APPROVED", summary: "Approved" },
  ],
  stepStates: [{ code: "ACTIVE", summary: "Waiting" }],
  limits: { maxSteps: 3, maxApproversPerStep: 5, maxOutcomeFields: 4, maxCommentLength: 2000, maxDelegationDays: 366 },
};

export const NO_ACTIONS: Actions = {
  canSubmit: false,
  canApprove: false,
  canReject: false,
  canReassign: false,
  canRecall: false,
  canCancel: false,
  canAssign: false,
  canReapply: false,
};

export const REQUEST_ID = "11111111-1111-4111-8111-111111111111";
export const RECORD_ID = "22222222-2222-4222-8222-222222222222";

export function summary(more: Partial<Summary> = {}): Summary {
  return {
    id: REQUEST_ID,
    processApiName: "Large__c",
    processLabel: "Large expense",
    processVersion: 1,
    objectApiName: "Expense__c",
    recordId: RECORD_ID,
    status: "PENDING",
    currentStep: 1,
    currentStepLabel: "Manager",
    stepCount: 2,
    submittedBy: { userId: "33333333-3333-4333-8333-333333333333", name: "User A" },
    startedAt: "2026-10-18T09:00:00Z",
    version: 3,
    ...more,
  };
}

export function task(more: Partial<Task> = {}): Task {
  return {
    assignmentId: "44444444-4444-4444-8444-444444444444",
    instance: summary(),
    objectLabel: "Expense",
    recordTitle: "Trip to the fair",
    recordReadable: true,
    stepLabel: "Manager",
    via: "DIRECT",
    status: "OPEN",
    since: "2026-10-18T09:00:00Z",
    actions: { ...NO_ACTIONS, canApprove: true, canReject: true, canReassign: true },
    ...more,
  };
}

export const PROCESS: Process = {
  apiName: "Large__c",
  objectApiName: "Expense__c",
  label: "Large expense",
  description: "",
  active: true,
  definitionVersion: 2,
  entryCriteria: "amount__c > 100",
  allowSelfApproval: false,
  steps: [
    { name: "Manager", label: "Manager", completion: "ANY", approvers: [{ kind: "OWNER" }] },
    { name: "Finance", label: "Finance", completion: "ALL", approvers: [{ kind: "USER", value: "u-1" }] },
  ],
  outcomes: {
    approved: [{ field: "status__c", value: "Approved" }],
    rejected: [],
    recalled: [],
  },
  version: 5,
};

/** The page of an answer with nothing after it. */
export const LAST_PAGE = { limit: 50, hasMore: false };
