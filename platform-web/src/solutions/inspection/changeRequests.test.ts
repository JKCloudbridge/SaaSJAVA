import { describe, expect, it } from "vitest";
import {
  becameWords,
  marksFromRequests,
  placeOf,
  placeWords,
  statusWords,
  type ChangeCommentView,
  type ChangeRequestView,
  type ChangeRequestsView,
} from "./changeRequests";

function comment(extra: Partial<ChangeCommentView> = {}): ChangeCommentView {
  return {
    id: "c",
    kind: "CELL",
    rowId: "r1",
    columnId: "c1",
    label: "Torque / First",
    inActiveVersion: true,
    text: "x",
    createdAt: "2026-10-22T08:00:00Z",
    mine: false,
    ...extra,
  };
}

function request(extra: Partial<ChangeRequestView> = {}): ChangeRequestView {
  return {
    id: "q",
    title: "T",
    status: "OPEN",
    versionNo: 1,
    mine: false,
    createdAt: "2026-10-22T08:00:00Z",
    placesTotal: 1,
    placesInActive: 1,
    canComment: false,
    canAccept: false,
    canDecline: false,
    canTurn: false,
    canReopen: false,
    comments: [comment()],
    ...extra,
  };
}

function view(requests: ChangeRequestView[]): ChangeRequestsView {
  return { requests, canReview: true, canRequest: true, activeVersion: 1, draftVersion: 0, openCount: 0 };
}

describe("what the grid marks", () => {
  it("marks the places of open and accepted requests that are still in the version in use", () => {
    const marks = marksFromRequests(
      view([
        request({ comments: [comment(), comment({ kind: "COLUMN", rowId: undefined, columnId: "c2" })] }),
        request({ status: "ACCEPTED", comments: [comment({ kind: "ROW", columnId: undefined })] }),
      ]),
    );

    expect(marks.cells.get("r1:c1")).toBe(1);
    expect(marks.columns.get("c2")).toBe(1);
    expect(marks.rows.get("r1")).toBe(1);
  });

  it("leaves out declined and finished requests, places that are gone and comments on the whole template", () => {
    const marks = marksFromRequests(
      view([
        request({ status: "DECLINED" }),
        request({ status: "DONE" }),
        request({ comments: [comment({ inActiveVersion: false }), comment({ kind: "TEMPLATE", rowId: undefined, columnId: undefined })] }),
      ]),
    );

    expect(marks.cells.size + marks.rows.size + marks.columns.size).toBe(0);
  });

  it("marks nothing before the requests are known", () => {
    const marks = marksFromRequests(undefined);

    expect(marks.cells.size + marks.rows.size + marks.columns.size).toBe(0);
  });
});

describe("a comment's place", () => {
  it("is written from the label kept when it was made", () => {
    expect(placeWords(comment({ kind: "ROW", label: "First" }))).toBe("Row “First”");
    expect(placeWords(comment({ kind: "COLUMN", label: "Torque" }))).toBe("Column “Torque”");
    expect(placeWords(comment({ kind: "CELL", label: "Torque / First" }))).toBe("Cell: Torque / First");
    expect(placeWords(comment({ kind: "TEMPLATE", label: "" }))).toBe("The whole template");
  });

  it("is still written when the place had no name", () => {
    expect(placeWords(comment({ kind: "ROW", label: " " }))).toBe("A row");
    expect(placeWords(comment({ kind: "COLUMN", label: "" }))).toBe("A column");
    expect(placeWords(comment({ kind: "CELL", label: "" }))).toBe("A cell");
  });

  it("is the identifiers of the grid, or nothing for the whole template", () => {
    expect(placeOf(comment())).toEqual({ rowId: "r1", columnId: "c1" });
    expect(placeOf(comment({ kind: "ROW", columnId: undefined }))).toEqual({ rowId: "r1", columnId: null });
    expect(placeOf(comment({ kind: "TEMPLATE", rowId: undefined, columnId: undefined }))).toBeNull();
  });
});

describe("the words of a request", () => {
  it("gives every status a word and a tone, and a status it does not know is shown as it is", () => {
    expect(statusWords("OPEN")).toEqual({ text: "Open", tone: "on" });
    expect(statusWords("DECLINED").tone).toBe("error");
    expect(statusWords("SOMETHING")).toEqual({ text: "SOMETHING", tone: "quiet" });
  });

  it("says which version a request became and what it is now", () => {
    expect(becameWords(request())).toBeNull();
    expect(becameWords(request({ resultingVersion: 3 }))).toBe("Version 3");
    expect(becameWords(request({ resultingVersion: 3, resultingVersionStatus: "DRAFT" }))).toBe("Version 3 (a draft)");
    expect(becameWords(request({ resultingVersion: 3, resultingVersionStatus: "REJECTED" }))).toBe("Version 3 (rejected)");
    expect(becameWords(request({ resultingVersion: 3, resultingVersionStatus: "ACTIVE" }))).toBe("Version 3 (in use)");
  });
});
