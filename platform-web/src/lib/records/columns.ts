import type { CSSProperties } from "react";
import type { FormSection } from "./types";

/**
 * A section whose two columns are named by the screen that draws it (FA11A, ADR-0205): the fields in `left` run down the
 * left column in that order and every other field runs down the right one. The API's own layout has no such notion (it
 * fills a two-column block row by row), so a screen that needs a fixed order of columns, such as the Details of a template,
 * builds its own sections and the generic record screens place them with {@link arrange}. A section without `left` is drawn
 * exactly as before.
 */
export type ArrangedSection = FormSection & { left?: readonly string[] };

/** The names of a section in drawing order, and where each one goes when the section is arranged in columns. */
export interface Arrangement {
  /** True when the section names its left column; the grid then needs the `record-grid-placed` class. */
  placed: boolean;
  names: string[];
  /** The grid position of a field (column 1 or 2, row from 1) as style variables; none for a section drawn row by row. */
  styleOf: (name: string) => CSSProperties | undefined;
}

/** Orders the names a screen is going to draw (already filtered to the visible ones) and says where each one sits. */
export function arrange(section: FormSection, names: readonly string[]): Arrangement {
  const left = (section as ArrangedSection).left;
  if (left === undefined) {
    return { placed: false, names: [...names], styleOf: () => undefined };
  }
  const leftNames = names.filter((name) => left.includes(name));
  const rightNames = names.filter((name) => !left.includes(name));
  const position = new Map<string, CSSProperties>();
  leftNames.forEach((name, index) => position.set(name, { "--col": 1, "--row": index + 1 } as CSSProperties));
  rightNames.forEach((name, index) => position.set(name, { "--col": 2, "--row": index + 1 } as CSSProperties));
  return { placed: true, names: [...leftNames, ...rightNames], styleOf: (name) => position.get(name) };
}
