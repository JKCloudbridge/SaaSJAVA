import { SIDES, type BorderStyle, type ResolvedLook } from "./look";
import type { Edges } from "./borders";
import { inkFor } from "./ink";

/**
 * How a cell is drawn from its look (SH4): a short list of FIXED class names and at most three custom properties, nothing
 * else. This is the one route by which something a sheet states reaches the page, so it is small on purpose:
 *
 *  - a colour is handed over only as the value of `--sheet-fill` or `--sheet-text` (set through the style object model by
 *    the component, never as markup), and only after `readLook` checked its shape; the classes `sheet-has-fill` and
 *    `sheet-has-text` read it;
 *  - a font is one of sixteen classes, a size one number in `--sheet-pt`, a border one of 24 classes;
 *  - the answer for one look is made once and kept, so a sheet of fifty thousand cells makes a few dozen descriptions.
 */

export interface CellStyleDescription {
  /** Fixed class names. */
  classes: readonly string[];
  /** Custom properties to set on the cell: `--sheet-fill`, `--sheet-text`, `--sheet-pt`. */
  props: Readonly<Record<string, string>>;
}

/** A colour the cell takes from a condition, over the look's own. */
export interface Painted {
  fill?: string;
  colour?: string;
}

export const FILL_PROPERTY = "--sheet-fill";
export const TEXT_PROPERTY = "--sheet-text";
export const SIZE_PROPERTY = "--sheet-pt";

const BORDER_CLASS: Record<BorderStyle, string> = {
  THIN: "thin",
  MEDIUM: "medium",
  THICK: "thick",
  DOTTED: "dotted",
  DASHED: "dashed",
  DOUBLE: "double",
};
const SIDE_LETTER = { top: "t", right: "r", bottom: "b", left: "l" } as const;

const MAX_KEPT = 5000;
const kept = new Map<string, CellStyleDescription>();

/** How many descriptions are kept (for the test that a sheet does not make a style for each cell). */
export function descriptionsKept(): number {
  return kept.size;
}

export function forgetDescriptions(): void {
  kept.clear();
}

/** The class that names the font of a key. */
export const fontClass = (key: string): string => `sheet-font-${key}`;

/**
 * The description of a look with the borders it draws and the colours a condition gives. A look with nothing in it and no
 * borders gives an empty description.
 */
export function describe(look: ResolvedLook, edges: Edges, painted?: Painted): CellStyleDescription {
  const fill = painted?.fill ?? look.fill;
  const colour = painted?.colour ?? look.colour;
  const key = [
    look.font ?? "",
    look.size ?? "",
    colour ?? "",
    fill ?? "",
    look.wrap ? "w" : "",
    look.vertical ?? "",
    SIDES.map((side) => edges[side] ?? "").join(","),
  ].join("|");
  const known = kept.get(key);
  if (known) {
    return known;
  }
  const classes: string[] = [];
  const props: Record<string, string> = {};
  if (look.font) classes.push(fontClass(look.font));
  if (look.size !== undefined) {
    classes.push("sheet-pt");
    props[SIZE_PROPERTY] = String(look.size);
  }
  if (fill) {
    classes.push("sheet-has-fill");
    props[FILL_PROPERTY] = fill;
  }
  if (colour) {
    classes.push("sheet-has-text");
    props[TEXT_PROPERTY] = colour;
  } else if (fill) {
    classes.push(inkFor(fill) === "dark" ? "sheet-ink-dark" : "sheet-ink-light");
  }
  if (look.wrap) classes.push("sheet-wrap");
  if (look.vertical) classes.push(look.vertical === "TOP" ? "sheet-v-top" : "sheet-v-middle");
  for (const side of SIDES) {
    const style = edges[side];
    if (style) classes.push(`sheet-b${SIDE_LETTER[side]}-${BORDER_CLASS[style]}`);
  }
  const made: CellStyleDescription = Object.freeze({ classes: Object.freeze(classes), props: Object.freeze(props) });
  if (kept.size >= MAX_KEPT) {
    kept.clear();
  }
  kept.set(key, made);
  return made;
}
