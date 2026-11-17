import { wireFromCases } from "@/lib/sheets/formula/caseFile";
import { json, refusal, type Handler } from "./fakeApi";

/**
 * A fill-in under review, for the tests of the reviewer (SH5.1): a sheet in format version 2 with two cells for the reviewer
 * (the marks, typed as text, as an uploaded form has them) and one formula cell that adds them up, and a fake server that
 * keeps what was saved and calculates from it, the way the real one does. Test data only.
 */

export const FILL_OBJECT = "InspectionFill";
export const FILL_ID = "0198f5c0-0000-7000-8000-000000000051";
export const FILL_URL = `/api/v1/sheet-fills/${FILL_OBJECT}/${FILL_ID}`;
export const FILL_OWNER = { object: FILL_OBJECT, id: FILL_ID };
export const TEMPLATE_ID = "0198f5c0-0000-7000-8000-000000000042";

/** The marks column is `c2`; the reviewer's cells are `r1:c2` and `r2:c2`; the formula adds them at `r3:c2`. */
export const SCORE_CELL = "r3:c2";

/** The sheet as the API sends it (format version 2). */
export function reviewerSheet(): unknown {
  return {
    formatVersion: 2,
    sheets: [
      {
        key: "main",
        dimension: 2,
        rowsMode: "FIXED",
        columns: [
          { id: "c1", label: "Item", type: "text" },
          { id: "c2", label: "Mark", type: "text" },
        ],
        headerRows: [{ id: "h1" }],
        rows: [{ id: "r1", label: "First" }, { id: "r2", label: "Second" }, { id: "r3", label: "Total" }],
        cells: {
          "h1:c1": { value: "Item" },
          "h1:c2": { value: "Mark" },
          "r1:c1": { value: "Fire exit", kind: "fixed" },
          "r2:c1": { value: "Alarm panel", kind: "fixed" },
          "r3:c1": { value: "Score", kind: "fixed" },
          [SCORE_CELL]: { kind: "fixed", formula: "SUM(@{c2,r1}:@{c2,r2})" },
        },
        merges: [],
        inputs: { "r1:c2": { class: "reviewer" }, "r2:c2": { class: "reviewer" } },
      },
    ],
  };
}

/** What the fake server holds and has been asked. */
export interface ReviewerServer {
  /** The saved cells by cell key. */
  cells: Record<string, string>;
  /** The row version of the fill-in. */
  version: number;
  /** How many times the answers of the formulas were asked for. */
  calculations: number;
  /** How many times the cells were saved. */
  saves: number;
  /** Refuse the next saves with this sentence (a refusal of the server). */
  refuse?: string;
  /** Added to the answer, so that a test can tell the server's answer from the browser's preview. */
  bonus?: number;
}

export function newServer(cells: Record<string, string> = {}): ReviewerServer {
  return { cells, version: 4, calculations: 0, saves: 0 };
}

export function summary(server: ReviewerServer, extra: Record<string, unknown> = {}) {
  return {
    fillId: FILL_ID,
    ownerObject: "InspectionTemplate",
    ownerId: TEMPLATE_ID,
    versionNo: 3,
    versionStatus: "ACTIVE",
    status: "SUBMITTED",
    version: server.version,
    rowCount: 0,
    cellsBytes: 28,
    cellsHash: "a".repeat(64),
    corrections: 0,
    createdAt: "2026-10-23T08:00:00Z",
    updatedAt: "2026-10-23T09:00:00Z",
    ...extra,
  };
}

const PLAIN_NUMBER = /^\s*[+-]?(\d+(\.\d*)?|\.\d+)\s*$/;

/** The answer of the one formula, from the saved cells: the sum of the marks that are numbers; text is skipped. */
function total(server: ReviewerServer): string {
  let sum = server.bonus ?? 0;
  for (const key of ["r1:c2", "r2:c2"]) {
    const mark = server.cells[key];
    if (mark !== undefined && PLAIN_NUMBER.test(mark)) sum += Number(mark);
  }
  return String(sum);
}

/** The handlers of a reviewer's fill-in: read, calculate and save, all from `server`. */
export function reviewerFill(server: ReviewerServer, view: { editAs?: string | null; canEdit?: boolean; fill?: Record<string, unknown> } = {}): Record<string, Handler> {
  const rows = () => {
    const byRow = new Map<string, Record<string, string>>();
    for (const [key, value] of Object.entries(server.cells)) {
      const [row, column] = key.split(":") as [string, string];
      byRow.set(row, { ...byRow.get(row), [column]: value });
    }
    return [...byRow].map(([id, cells]) => ({ id, cells }));
  };
  return {
    [`GET ${FILL_URL}`]: () =>
      json({
        data: {
          fill: summary(server, view.fill),
          grid: reviewerSheet(),
          cells: { formatVersion: 3, sheets: [{ key: "main", rows: rows() }] },
          canEdit: view.canEdit ?? true,
          correction: false,
          editAs: view.editAs === undefined ? "REVIEWER" : view.editAs,
          limits: { maxBytes: 1_000_000, maxRows: 1000, maxCellChars: 1000, formulas: wireFromCases() },
        },
      }),
    [`GET ${FILL_URL}/calculation`]: () => {
      server.calculations += 1;
      return json({
        data: { bodyRows: 0, cells: [{ cell: SCORE_CELL, answer: { kind: "number", value: total(server) } }], columns: [] },
      });
    },
    [`PUT ${FILL_URL}/cells`]: async (request) => {
      if (server.refuse) {
        return refusal("VALIDATION_ERROR", server.refuse, 422);
      }
      const sent = JSON.parse(await request.text()) as { sheets: { rows: { id: string; cells: Record<string, string> }[] }[] };
      server.cells = {};
      for (const row of sent.sheets[0]?.rows ?? []) {
        for (const [column, value] of Object.entries(row.cells)) server.cells[`${row.id}:${column}`] = value;
      }
      server.version += 1;
      server.saves += 1;
      return json({ data: summary(server) });
    },
  };
}
