import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  downloadName,
  downloadNameFor,
  footerText,
  footerDay,
  footerTextFor,
  footerTextWithDay,
  revisionNumber,
  revisionOf,
  revisionWords,
  revisionWordsOf,
  versionTitle,
} from "./revision";

// The same cases are checked on the server's side (TemplateSheetTextsTest), so the screen and the file always agree.
describe("the revision as people read it", () => {
  it("is the stored number minus one, with two digits", () => {
    expect(revisionNumber(1)).toBe("00");
    expect(revisionNumber(2)).toBe("01");
    expect(revisionNumber(10)).toBe("09");
    expect(revisionNumber(100)).toBe("99");
    expect(revisionNumber(101)).toBe("100");
  });

  it("is never negative", () => {
    expect(revisionNumber(0)).toBe("00");
    expect(revisionNumber(-4)).toBe("00");
  });

  it("names a version in a sentence", () => {
    expect(revisionWords(3)).toBe("Revision 02");
  });
});

describe("the footer row text", () => {
  it("is the number, two spaces and the revision in single quotes", () => {
    expect(footerText("F/MR/16", 2)).toBe("F/MR/16  Rev No.'01'");
    expect(footerText("  F/MR/16 ", 1)).toBe("F/MR/16  Rev No.'00'");
  });

  it("is blank until the template has a number", () => {
    expect(footerText(null, 1)).toBe("");
    expect(footerText(undefined, 1)).toBe("");
    expect(footerText("", 1)).toBe("");
    expect(footerText("   ", 3)).toBe("");
  });
});

describe("the name a downloaded sheet is saved under", () => {
  it("is the number with hyphens and the revision", () => {
    expect(downloadName("F/MR/16", 1)).toBe("F-MR-16_Rev00.xlsx");
    expect(downloadName("CP/PRD/A-12", 3)).toBe("CP-PRD-A-12_Rev02.xlsx");
    expect(downloadName(null, 1)).toBe("Template_Rev00.xlsx");
  });

  it("can never hold a path or a break", () => {
    const name = downloadName('../../x\r\nSet-Cookie: a=b/"q"', 1);
    expect(name).not.toMatch(/[/\\\r\n" ]/);
    expect(name.endsWith("_Rev00.xlsx")).toBe(true);
  });
});

// FA8 (ADR-0202): the server gives a version its revision number when it first becomes the one in use, so a copy that was
// rejected never uses one and the numbers have no gaps. A version in use before numbers were stored reads as its stored
// number minus one.
describe("the revision number the server stored", () => {
  it("is used when there is one, and the stored version minus one when there is not", () => {
    expect(revisionOf({ versionNo: 4, revisionNo: 1 })).toBe("01");
    expect(revisionOf({ versionNo: 4 })).toBe("03");
    expect(revisionOf({ versionNo: 4, revisionNo: null })).toBe("03");
    expect(revisionWordsOf({ versionNo: 4, revisionNo: 1 })).toBe("Revision 01");
  });

  it("is never negative", () => {
    expect(revisionOf({ versionNo: 1, revisionNo: -2 })).toBe("00");
  });
});

describe("what a version is called in a list", () => {
  it("is its revision once it was in use, whatever it is now", () => {
    expect(versionTitle({ versionNo: 4, status: "ACTIVE", revisionNo: 1 })).toBe("Revision 01");
    expect(versionTitle({ versionNo: 2, status: "SUPERSEDED", revisionNo: 0 })).toBe("Revision 00");
    expect(versionTitle({ versionNo: 5, status: "APPROVED", revisionNo: 2 })).toBe("Revision 02");
    expect(versionTitle({ versionNo: 3, status: "RETIRED" })).toBe("Revision 02");
  });

  it("is what it is before it was ever in use, because a copy is not a revision until it is published", () => {
    expect(versionTitle({ versionNo: 2, status: "DRAFT" })).toBe("Draft");
    expect(versionTitle({ versionNo: 2, status: "IN_REVIEW" })).toBe("In review");
    expect(versionTitle({ versionNo: 2, status: "APPROVED" })).toBe("Approved, not published");
    expect(versionTitle({ versionNo: 2, status: "REJECTED" })).toBe("Rejected copy");
    expect(versionTitle({ versionNo: 2, status: "WITHDRAWN" })).toBe("Withdrawn copy");
  });
});

describe("the file and the footer for a revision written with its digits", () => {
  it("use the revision the server gave", () => {
    expect(downloadNameFor("F/MR/16", "01")).toBe("F-MR-16_Rev01.xlsx");
    expect(footerTextFor("F/MR/16", "01")).toBe("F/MR/16  Rev No.'01'");
    expect(footerTextFor(null, "01")).toBe("");
  });
});

// The footer with the day (MC1, ADR-0257): the cases are the file the server's test reads too, so the screen, the print
// and the downloaded file write the same words.
describe("the footer with the day of the Revision", () => {
  const cases = readFileSync(
    path.resolve(process.cwd(), "../platform-app/src/test/resources/inspection/footer-cases.txt"),
    "utf8",
  )
    .split(/\r?\n/)
    .filter((line) => line.trim() !== "" && !line.startsWith("#"))
    .map((line) => line.split("|"));

  it("has the cases to check", () => {
    expect(cases.length).toBeGreaterThanOrEqual(8);
  });

  it.each(cases)("writes %s | %s | %s | %s as the server does", (number, revision, instant, zone, expected) => {
    const text = footerTextWithDay(
      number!.trim(),
      String(Number(revision!.trim())).padStart(2, "0"),
      instant!.trim() === "" ? undefined : instant!.trim(),
      zone!.trim(),
    );
    expect(text).toBe(expected!.trim().replace(/\\n/g, "\n"));
  });

  it("writes the day in the reader's own zone when none is given and refuses a text that is no instant", () => {
    expect(footerDay("2026-11-13T10:15:00Z", "UTC")).toBe("13-Nov-2026");
    expect(footerDay("not a date")).toBeUndefined();
    expect(footerTextWithDay("F/MR/16", "00", "not a date")).toBe("F/MR/16  Rev No.'00'");
  });
});
