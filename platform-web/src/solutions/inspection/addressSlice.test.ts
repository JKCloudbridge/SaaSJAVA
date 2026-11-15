import { describe, expect, it } from "vitest";
import { addressSlice, both, sliceKey, sliceWords } from "./addressSlice";

const slice = (text: string) => addressSlice(new URLSearchParams(text));

describe("what the address asks the list of fill-ins to show", () => {
  it("is no narrowing for an address with nothing in it", () => {
    const none = slice("");
    expect(none.filter).toBeUndefined();
    expect(none.waiting).toBeUndefined();
    expect(none.words).toEqual([]);
  });

  it("narrows by month and plant on the fields of the header", () => {
    expect(slice("period=2026-11&plant=PL1").filter).toEqual({
      op: "AND",
      items: [
        { op: "EQ", field: "period", values: ["2026-11"] },
        { op: "EQ", field: "plant", values: ["PL1"] },
      ],
    });
  });

  it("leaves out a value of a shape the server does not know, never guessing", () => {
    expect(slice("period=2026-13&plant=a b c").filter).toBeUndefined();
    expect(slice("waiting=sideways").waiting).toBeUndefined();
  });

  it("asks for fill-ins that were sent: waiting or decided, not drafts and not returned ones", () => {
    expect(slice("sent=1").filter).toEqual({ op: "IN", field: "status", values: ["SUBMITTED", "APPROVED"] });
  });

  it("asks for the fill-ins decided to need an Action plan: the result, among those that were sent, and no Action plans", () => {
    expect(slice("needs=1").filter).toEqual({
      op: "AND",
      items: [
        { op: "EQ", field: "result", values: ["ACTION_PLAN"] },
        { op: "IN", field: "status", values: ["SUBMITTED", "APPROVED"] },
        { op: "IS_EMPTY", field: "parentFill" },
      ],
    });
  });

  it("tells ordinary fill-ins from Action plans that are not approved yet", () => {
    expect(slice("plan=none").filter).toEqual({ op: "IS_EMPTY", field: "parentFill" });
    expect(slice("plan=open").filter).toEqual({
      op: "AND",
      items: [
        { op: "IS_NOT_EMPTY", field: "parentFill" },
        { op: "NE", field: "status", values: ["APPROVED"] },
      ],
    });
  });

  it("passes the step a fill-in waits at to the server, which alone knows it", () => {
    expect(slice("waiting=top").waiting).toBe("top");
    expect(slice("waiting=review").waiting).toBe("review");
    expect(slice("waiting=top").filter).toBeUndefined();
  });

  it("says in plain words what the list was narrowed to", () => {
    expect(sliceWords(slice("period=2026-11&sent=1"))).toBe("the month 2026-11, fill-ins that were sent");
    expect(sliceWords(slice("waiting=top"))).toBe("fill-ins waiting for top management");
  });

  it("gives two equal slices one key, and joins two conditions with AND", () => {
    expect(sliceKey(slice("sent=1&period=2026-11"))).toBe(sliceKey(slice("period=2026-11&sent=1")));
    expect(sliceKey(slice("sent=1"))).not.toBe(sliceKey(slice("needs=1")));
    expect(both(undefined, undefined)).toBeUndefined();
    expect(both({ op: "EQ", field: "a", values: [1] }, undefined)).toEqual({ op: "EQ", field: "a", values: [1] });
    expect(both({ op: "EQ", field: "a", values: [1] }, { op: "EQ", field: "b", values: [2] })).toEqual({
      op: "AND",
      items: [
        { op: "EQ", field: "a", values: [1] },
        { op: "EQ", field: "b", values: [2] },
      ],
    });
  });
});
