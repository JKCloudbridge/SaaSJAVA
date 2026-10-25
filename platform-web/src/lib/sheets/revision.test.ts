import { describe, expect, it } from "vitest";
import { downloadName, footerText, revisionNumber, revisionWords } from "./revision";

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
