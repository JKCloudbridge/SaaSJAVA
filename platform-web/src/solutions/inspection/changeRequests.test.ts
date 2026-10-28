import { describe, expect, it } from "vitest";
import {
  becameWords,
  statusWords,
  type ChangeCommentView,
  type ChangeRequestView,
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
    canCreateCopy: false,
    canChangeCopy: false,
    comments: [comment()],
    templateId: "t-1",
    ...extra,
  };
}

describe("the words of a request", () => {
  it("gives every status a word and a tone, and a status it does not know is shown as it is", () => {
    expect(statusWords("OPEN")).toEqual({ text: "In progress", tone: "on" });
    expect(statusWords("DONE")).toEqual({ text: "Published", tone: "ok" });
    expect(statusWords("DECLINED").tone).toBe("error");
    expect(statusWords("SOMETHING")).toEqual({ text: "SOMETHING", tone: "quiet" });
  });

  it("says what the revised sheet of a request is while it is prepared and once it is published", () => {
    expect(becameWords(request())).toBeNull();
    expect(becameWords(request({ resultingVersion: 3 }))).toBe("Revision 02");
    expect(becameWords(request({ resultingVersion: 3, resultingVersionStatus: "DRAFT" }))).toBe(
      "The revised sheet (the revised copy, being prepared)",
    );
    expect(becameWords(request({ resultingVersion: 3, resultingVersionStatus: "REJECTED" }))).toBe(
      "The revised sheet (the revised copy, rejected)",
    );
    expect(
      becameWords(
        request({ status: "DONE", resultingVersion: 4, resultingRevision: 1, resultingVersionStatus: "ACTIVE" }),
      ),
    ).toBe("Revision 01, in use");
    expect(
      becameWords(
        request({ status: "DONE", resultingVersion: 4, resultingRevision: 1, resultingVersionStatus: "SUPERSEDED" }),
      ),
    ).toBe("Revision 01, replaced since");
  });
});
