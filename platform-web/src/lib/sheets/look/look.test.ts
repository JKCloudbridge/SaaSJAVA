import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { BORDER_STYLES, FONT_KEYS, NO_LOOK, NUMBER_FORMAT_KEYS, readLook, VERTICAL_ALIGNMENTS } from "./look";
import { OPERATORS } from "./conditions";

const root = path.resolve(process.cwd(), "..");
const schema = JSON.parse(readFileSync(path.join(root, "platform-api-contract/src/main/resources/schemas/sheet-grid.json"), "utf8")) as {
  schemas: Record<string, { properties?: Record<string, { enum?: string[]; $ref?: string; pattern?: string }> }>;
};
const css = readFileSync(path.join(process.cwd(), "src/app/globals.css"), "utf8");

// The look of a cell as the page reads it (SH4). The server checks every part; the page checks again and never fails.
describe("reading the stored look", () => {
  it("keeps what is exactly allowed, with the colours in lower case", () => {
    const look = readLook({
      font: "SERIF_TEXT",
      size: 14,
      colour: "#AABBCC",
      fill: "#0A0b0C",
      wrap: true,
      verticalAlignment: "TOP",
      borders: { top: "THIN", right: "DOUBLE" },
      numberFormat: "PERCENT",
    });

    expect(look).toEqual({
      font: "SERIF_TEXT",
      size: 14,
      colour: "#aabbcc",
      fill: "#0a0b0c",
      wrap: true,
      vertical: "TOP",
      borders: { top: "THIN", right: "DOUBLE" },
      numberFormat: "PERCENT",
    });
  });

  it("drops every part that is not exactly the allowed shape, and never throws", () => {
    const hostile = {
      font: "Comic Sans",
      size: 7.5,
      colour: "red; background: url(x)",
      fill: "#12345",
      wrap: "yes",
      verticalAlignment: "CENTER",
      borders: { top: "HEAVY", left: 3, bottom: null },
      numberFormat: "CURRENCY",
    };

    expect(readLook(hostile)).toEqual({ borders: {}, numberFormat: "GENERAL" });
    for (const value of [null, undefined, 5, "text", [], { size: 8 + 21 }, { size: 7 }, { size: "12" }, { fill: "#12345g" }, { fill: "#1234567" }]) {
      expect(() => readLook(value)).not.toThrow();
    }
    expect(readLook({ fill: "#12345g" }).fill).toBeUndefined();
    expect(readLook({ fill: " #123456" }).fill).toBeUndefined();
    expect(readLook({ fill: "#123456\n" }).fill).toBeUndefined();
  });

  it("gives the shared empty look for a look that says nothing, and reads one stored object once", () => {
    expect(readLook(undefined)).toBe(NO_LOOK);
    const stored = { font: "MONO", size: 10 };
    expect(readLook(stored)).toBe(readLook(stored));
  });

  it("states a bottom alignment by saying nothing (the format's default)", () => {
    expect(readLook({ verticalAlignment: "BOTTOM" }).vertical).toBeUndefined();
    expect(readLook({ verticalAlignment: "MIDDLE" }).vertical).toBe("MIDDLE");
  });
});

// A key written in three places (the server's schema, this code, the stylesheet) must be one list.
describe("the closed lists", () => {
  const look = schema.schemas.SheetLook?.properties;

  it("are the lists of the schema the server validates against", () => {
    expect([...FONT_KEYS]).toEqual(look?.font?.enum);
    expect([...NUMBER_FORMAT_KEYS]).toEqual(look?.numberFormat?.enum);
    expect([...VERTICAL_ALIGNMENTS]).toEqual(look?.verticalAlignment?.enum);
    expect([...OPERATORS]).toEqual(schema.schemas.SheetCondition?.properties?.operator?.enum);
    const borderStyles = schema.schemas.SheetBorderStyle as unknown as { enum: string[] };
    expect([...BORDER_STYLES]).toEqual(borderStyles.enum);
  });

  it("have a font class and a border class in the stylesheet for every key", () => {
    for (const key of FONT_KEYS) {
      expect(css, key).toContain(`.sheet-font-${key} {`);
    }
    for (const side of ["t", "r", "b", "l"]) {
      for (const style of BORDER_STYLES) {
        expect(css, `${side} ${style}`).toContain(`.sheet-b${side}-${style.toLowerCase()} {`);
      }
    }
  });

  it("have a colour shape equal to the schema's", () => {
    expect(look?.colour?.pattern).toBe("^#[0-9a-fA-F]{6}$");
    expect(look?.fill?.pattern).toBe("^#[0-9a-fA-F]{6}$");
  });
});
