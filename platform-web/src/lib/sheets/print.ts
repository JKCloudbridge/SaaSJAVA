import { cellKind, cellKey, DEFAULT_COLUMN_WIDTH, displayRows, indexesOf, mergeRects } from "./geometry";
import { edgesOf } from "./look/borders";
import { describe } from "./look/cellStyle";
import { judge, readConditions } from "./look/conditions";
import { displayOf, type Answer } from "./look/display";
import { readLook } from "./look/look";
import type { AnswerKind } from "./look/numberFormat";
import { isLooked, lookSource, type AnswerMaps } from "./look/sheetLook";
import type { Alignment, CellStyle, FontSize, GridCell, GridDoc, TextColour, Zone } from "./types";

/**
 * The print view of a sheet (FA7, ADR-0201), as a plain model the component only draws: which columns, which header rows
 * repeat on every page, which body rows, which cells are merged, what each cell says and how it looks. Pure, so it is
 * tested without a browser. It is the sheet as a person reads it with the input cells empty; a template shows no value a
 * filler would type. The footer row is not here: it is made when the page is drawn, from the number and the revision.
 */

/** A sheet wider than this (pixels, all columns together) is printed landscape; A4 portrait holds about this much. */
export const PORTRAIT_WIDTH = 720;

export type Orientation = "portrait" | "landscape";

export interface PrintCell {
  key: string;
  text: string;
  rowSpan: number;
  colSpan: number;
  header: boolean;
  style: CellStyle;
  /** What a sheet in format version 2 adds (SH4): its look as fixed classes and custom properties, the alignment and the note for a screen reader. */
  look?: { classes: readonly string[]; props: Readonly<Record<string, string>>; alignment: Alignment; words: string | null };
}

export interface PrintRow {
  id: string;
  zone: Zone | "labels";
  height: number;
  cells: PrintCell[];
}

export interface PrintColumn {
  id: string;
  label: string;
  /** The share of the page width, in percent, so the sheet is fitted to the width of the page. */
  percent: number;
  /** The width in pixels, for a sheet that is not fitted to the width of the page. */
  width: number;
}

export interface PrintModel {
  orientation: Orientation;
  /** True for a sheet in format version 2: it is drawn from its look, with only the borders it states. */
  looked: boolean;
  /** The sheet's own page set-up (SH1A): whether it is fitted to the width of the page and whether its header rows repeat. */
  fitToWidth: boolean;
  repeatHeaderRows: boolean;
  columns: PrintColumn[];
  /** The band of column labels and the header rows: repeated on every page. */
  head: PrintRow[];
  body: PrintRow[];
}

/** What a cell shows: text as it is, a yes or no in words. */
function shown(value: GridCell["value"]): string {
  if (value === true) {
    return "Yes";
  }
  if (value === false) {
    return "No";
  }
  return value ?? "";
}

/** The width of all columns together, in pixels. */
export function sheetWidth(doc: GridDoc): number {
  return doc.columns.reduce((sum, column) => sum + (column.width ?? DEFAULT_COLUMN_WIDTH), 0);
}

export function orientationOf(doc: GridDoc): Orientation {
  return sheetWidth(doc) > PORTRAIT_WIDTH ? "landscape" : "portrait";
}

const LABEL_HEIGHT = 28;

/**
 * `labels: false` leaves out the band of column labels (a page that has no headings, such as the register). A sheet in
 * format version 2 has no band unless `labels: true` asks for one (SH5.1, ADR-0263): it is a form, printed as the form
 * looks on paper, and its stored column names (often "Column 6") are not part of it.
 * `showInput: true` shows what a filler typed into the input cells (a fill-in); a template leaves them empty.
 */
export function printModel(
  doc: GridDoc,
  options: { labels?: boolean; showInput?: boolean; answers?: AnswerMaps } = {},
): PrintModel {
  const rows = displayRows(doc);
  const indexes = indexesOf(doc, rows);
  const rects = mergeRects(doc, indexes);
  const looked = isLooked(doc);
  const source = lookSource(doc, rows);
  const rules = looked
    ? readConditions(doc.sheet?.conditions, (id) => indexes.rowPos.get(id), (id) => indexes.colPos.get(id))
    : [];
  const page = doc.sheet?.print;
  const anchors = new Map(rects.map((rect) => [`${rect.r0}:${rect.c0}`, rect]));
  const covered = new Set<string>();
  for (const rect of rects) {
    for (let r = rect.r0; r <= rect.r1; r++) {
      for (let c = rect.c0; c <= rect.c1; c++) {
        if (r !== rect.r0 || c !== rect.c0) {
          covered.add(`${r}:${c}`);
        }
      }
    }
  }
  const total = sheetWidth(doc);
  const columns: PrintColumn[] = doc.columns.map((column) => ({
    id: column.id,
    label: column.label,
    percent: ((column.width ?? DEFAULT_COLUMN_WIDTH) / total) * 100,
    width: column.width ?? DEFAULT_COLUMN_WIDTH,
  }));
  const labels: PrintRow = {
    id: "labels",
    zone: "labels",
    height: LABEL_HEIGHT,
    cells: doc.columns.map((column) => ({
      key: `label:${column.id}`,
      text: column.label,
      rowSpan: 1,
      colSpan: 1,
      header: true,
      style: { bold: true, alignment: "CENTER" },
    })),
  };
  const drawn: PrintRow[] = rows.map((row, r) => {
    const cells: PrintCell[] = [];
    doc.columns.forEach((column, c) => {
      if (covered.has(`${r}:${c}`)) {
        return;
      }
      const cell = doc.cells[cellKey(row.id, column.id)];
      const input = cellKind(row.zone, cell) === "input";
      const area = anchors.get(`${r}:${c}`);
      const key = cellKey(row.id, column.id);
      // A template shows its input cells empty: what a filler will type is not part of the sheet.
      let text = input && !options.showInput ? "" : shown(cell?.value);
      let look: PrintCell["look"];
      if (looked) {
        const own = readLook(doc.sheet?.cells[key]?.look);
        const answer = options.answers?.text[key];
        const shownAnswer: Answer | undefined =
          answer === undefined ? undefined : { text: answer, kind: kindOf(options.answers?.kinds[key]) };
        const display = displayOf(doc, row.id, column.id, row.zone, input && !options.showInput ? undefined : cell, own, shownAnswer, false);
        text = display.quiet ? "" : display.text;
        const hit = judge(rules, r, c, display.number);
        look = {
          ...describeFor(own, edgesOf(source, r, c, area), hit),
          alignment: cell?.style?.alignment ?? (display.number !== null ? "RIGHT" : "LEFT"),
          words: hit ? hit.words : null,
        };
      }
      cells.push({
        key,
        text,
        rowSpan: area ? area.r1 - area.r0 + 1 : 1,
        colSpan: area ? area.c1 - area.c0 + 1 : 1,
        header: row.zone === "header",
        style: cell?.style ?? {},
        ...(look ? { look } : {}),
      });
    });
    return { id: row.id, zone: row.zone, height: row.height, cells };
  });
  return {
    orientation: orientationOf(doc),
    looked,
    fitToWidth: page?.fitToWidth !== false,
    repeatHeaderRows: page?.repeatHeaderRows !== false,
    columns,
    head: [...((options.labels ?? !looked) ? [labels] : []), ...drawn.filter((row) => row.zone === "header")],
    body: drawn.filter((row) => row.zone !== "header"),
  };
}

function kindOf(kind: string | undefined): AnswerKind {
  return kind === "number" || kind === "boolean" || kind === "error" || kind === "blank" ? kind : "text";
}

function describeFor(
  own: ReturnType<typeof readLook>,
  edges: ReturnType<typeof edgesOf>,
  hit: ReturnType<typeof judge>,
): { classes: readonly string[]; props: Readonly<Record<string, string>> } {
  const made = describe(own, edges, hit ?? undefined);
  return { classes: made.classes, props: made.props };
}

export const SIZE_CLASS: Record<FontSize, string> = {
  SMALL: "sheet-size-small",
  NORMAL: "",
  LARGE: "sheet-size-large",
  TITLE: "sheet-size-title",
};
export const ALIGN_CLASS: Record<Alignment, string> = {
  LEFT: "sheet-print-left",
  CENTER: "sheet-print-center",
  RIGHT: "sheet-print-right",
};
export const COLOUR_CLASS: Record<TextColour, string> = {
  DEFAULT: "",
  ACCENT: "sheet-colour-accent",
  SUCCESS: "sheet-colour-success",
  DANGER: "sheet-colour-danger",
};
