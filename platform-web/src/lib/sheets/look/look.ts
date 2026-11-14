/**
 * The look of a cell of a sheet in format version 2, read from what the server stored (SH4, ADR-0251). The server checks
 * every part when it stores a sheet; the page checks again, so a document whose look is wrong in shape (it cannot be, but
 * the page does not rely on it) draws the cell without the bad part and never fails. Nothing from a sheet reaches the page
 * as markup or as a style the page builds from text: a colour only after the exact-shape check, a font only as a key of a
 * closed list, a size only as a whole number, a border and a number format only as keys of closed lists.
 */

/** The fonts of the closed list. The same sixteen keys as the server's `SheetLook.FONTS` and the schema; a test holds them together. */
export const FONT_KEYS = [
  "SANS_NEUTRAL",
  "SANS_HUMANIST",
  "SANS_WIDE",
  "SANS_FRIENDLY",
  "SANS_UNICODE",
  "DISPLAY_HEAVY",
  "DISPLAY_IMPACT",
  "SERIF_TEXT",
  "SERIF_SCREEN",
  "SERIF_SLAB",
  "MONO_CLASSIC",
  "MONO_CODE",
  "SANS_UI",
  "SANS",
  "SERIF",
  "MONO",
] as const;
export type FontKey = (typeof FONT_KEYS)[number];

export const NUMBER_FORMAT_KEYS = [
  "GENERAL",
  "WHOLE",
  "TWO_DECIMALS",
  "PERCENT",
  "PERCENT_TWO_DECIMALS",
  "THOUSANDS",
  "THOUSANDS_TWO_DECIMALS",
  "ACCOUNTING",
  "MONTH_YEAR",
  "SHORT_DATE",
] as const;
export type NumberFormatKey = (typeof NUMBER_FORMAT_KEYS)[number];

export const BORDER_STYLES = ["THIN", "MEDIUM", "THICK", "DOTTED", "DASHED", "DOUBLE"] as const;
export type BorderStyle = (typeof BORDER_STYLES)[number];

export const VERTICAL_ALIGNMENTS = ["TOP", "MIDDLE", "BOTTOM"] as const;
export type VerticalAlignment = (typeof VERTICAL_ALIGNMENTS)[number];

export const SIDES = ["top", "right", "bottom", "left"] as const;
export type Side = (typeof SIDES)[number];

export const MIN_POINTS = 8;
export const MAX_POINTS = 28;

/** The one shape a colour may have before it is handed to the page. Anything else is dropped. */
export const HEX_COLOUR = /^#[0-9a-fA-F]{6}$/;

export interface ResolvedLook {
  font?: FontKey;
  /** Whole points, 8 to 28. */
  size?: number;
  /** The text colour, `#rrggbb` in lower case. */
  colour?: string;
  /** The background colour, `#rrggbb` in lower case. */
  fill?: string;
  wrap?: boolean;
  vertical?: VerticalAlignment;
  borders: Partial<Record<Side, BorderStyle>>;
  numberFormat: NumberFormatKey;
}

/** A look that says nothing. */
export const NO_LOOK: ResolvedLook = Object.freeze({ borders: Object.freeze({}), numberFormat: "GENERAL" }) as ResolvedLook;

function oneOf<T extends string>(list: readonly T[], value: unknown): T | undefined {
  return typeof value === "string" ? list.find((candidate) => candidate === value) : undefined;
}

/** A colour in the one allowed shape, in lower case, or undefined. */
export function checkedColour(value: unknown): string | undefined {
  return typeof value === "string" && HEX_COLOUR.test(value) ? value.toLowerCase() : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const resolved = new WeakMap<object, ResolvedLook>();

/**
 * Reads the stored look of a cell. Never throws. A part that is not exactly what the format allows is left out; a look
 * that says nothing is the shared {@link NO_LOOK}. The answer for one stored object is kept, so a sheet of fifty thousand
 * cells reads each look once.
 */
export function readLook(raw: unknown): ResolvedLook {
  if (!isRecord(raw)) {
    return NO_LOOK;
  }
  const known = resolved.get(raw);
  if (known) {
    return known;
  }
  const out: ResolvedLook = { borders: {}, numberFormat: "GENERAL" };
  const font = oneOf(FONT_KEYS, raw.font);
  if (font) out.font = font;
  const size = raw.size;
  if (typeof size === "number" && Number.isInteger(size) && size >= MIN_POINTS && size <= MAX_POINTS) out.size = size;
  const colour = checkedColour(raw.colour);
  if (colour) out.colour = colour;
  const fill = checkedColour(raw.fill);
  if (fill) out.fill = fill;
  if (raw.wrap === true) out.wrap = true;
  const vertical = oneOf(VERTICAL_ALIGNMENTS, raw.verticalAlignment);
  if (vertical && vertical !== "BOTTOM") out.vertical = vertical;
  if (isRecord(raw.borders)) {
    for (const side of SIDES) {
      const style = oneOf(BORDER_STYLES, raw.borders[side]);
      if (style) out.borders[side] = style;
    }
  }
  const format = oneOf(NUMBER_FORMAT_KEYS, raw.numberFormat);
  if (format) out.numberFormat = format;
  resolved.set(raw, out);
  return out;
}
