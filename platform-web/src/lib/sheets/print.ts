import { cellKind, cellKey, DEFAULT_COLUMN_WIDTH, displayRows, indexesOf, mergeRects } from "./geometry";
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
}

export interface PrintModel {
  orientation: Orientation;
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

export function printModel(doc: GridDoc): PrintModel {
  const rows = displayRows(doc);
  const indexes = indexesOf(doc, rows);
  const rects = mergeRects(doc, indexes);
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
      cells.push({
        key: cellKey(row.id, column.id),
        // A template shows its input cells empty: what a filler will type is not part of the sheet.
        text: input ? "" : shown(cell?.value),
        rowSpan: area ? area.r1 - area.r0 + 1 : 1,
        colSpan: area ? area.c1 - area.c0 + 1 : 1,
        header: row.zone === "header",
        style: cell?.style ?? {},
      });
    });
    return { id: row.id, zone: row.zone, height: row.height, cells };
  });
  return {
    orientation: orientationOf(doc),
    columns,
    head: [labels, ...drawn.filter((row) => row.zone === "header")],
    body: drawn.filter((row) => row.zone !== "header"),
  };
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
