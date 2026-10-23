"use client";

import { useVirtualizer } from "@tanstack/react-virtual";
import { useEffect, useId, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent, type MouseEvent } from "react";
import { flushSync } from "react-dom";
import {
  at,
  cellKey,
  cellKind,
  contains,
  DEFAULT_COLUMN_WIDTH,
  indexesOf,
  LABEL_BAND_HEIGHT,
  mergeRects,
  type MergeRect,
} from "@/lib/sheets/geometry";
import type { AnnotationMarks } from "@/lib/sheets/annotate";
import { parseClipboard } from "@/lib/sheets/gridModel";
import type { GridCell } from "@/lib/sheets/types";
import type { SheetGridEditor } from "./useSheetEditor";

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

/** The class that gives a cell its size, one of the four names. */
const SIZE_CLASS = { SMALL: "sheet-size-small", NORMAL: "", LARGE: "sheet-size-large", TITLE: "sheet-size-title" } as const;

interface Editing {
  row: number;
  column: number;
  initial: string;
}

export interface SheetGridProps {
  editor: SheetGridEditor;
  /** True when the grid may be looked at and copied from but not changed. */
  readOnly: boolean;
  /**
   * Fill mode (FF1, ADR-0192): whether a person may type into the cell of this row and column. The grid of an author
   * leaves it out (any body cell takes text); the grid of a filler passes the sheet's own rule, so a cell the sheet fixes
   * is not opened. It only spares the person a refusal: the editor and the server decide again.
   */
  canEditCell?: (rowId: string, columnId: string) => boolean;
  /** The name of the grid for a screen reader. */
  label: string;
  /**
   * Annotate mode (FA5, ADR-0191): the grid stays read-only, the places that have notes are marked, a click on a column label
   * selects that column, and Enter or a double click on a cell asks the caller to open its box for a note. It decides nothing.
   */
  annotation?: { marks: AnnotationMarks; onActivate(): void };
}

/** The words a screen reader adds to a place that has notes. */
function notesWords(count: number | undefined): string {
  return count === undefined ? "" : count === 1 ? "1 note" : `${count} notes`;
}

/**
 * Our own grid (FA3, ADR-0172): a table-based editor of the canonical document that draws only the rows and columns on
 * the screen. It follows the ARIA grid pattern (grid, row, columnheader, gridcell; one tab stop; the active cell named by
 * `aria-activedescendant`; rows and columns counted for a reader), takes its look from the design tokens through the
 * `sheet-*` classes, and makes no request. Every change goes through the editor, which uses the pure operations of the
 * model; the grid only turns a key, a click or a paste into one of them.
 */
export function SheetGrid({ editor, readOnly, canEditCell, label, annotation }: SheetGridProps) {
  const { doc, rows, selection, rect } = editor;
  const gridId = useId();
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const dragging = useRef(false);
  const lastTap = useRef(0);
  const [editing, setEditing] = useState<Editing | null>(null);

  const indexes = useMemo(() => indexesOf(doc, rows), [doc, rows]);
  const rects = useMemo(() => mergeRects(doc, indexes), [doc, indexes]);
  const { anchors, covered } = useMemo(() => {
    const anchorMap = new Map<string, MergeRect>();
    const coveredSet = new Set<string>();
    for (const m of rects) {
      anchorMap.set(`${m.r0}:${m.c0}`, m);
      for (let r = m.r0; r <= m.r1; r++) {
        for (let c = m.c0; c <= m.c1; c++) {
          if (r !== m.r0 || c !== m.c0) {
            coveredSet.add(`${r}:${c}`);
          }
        }
      }
    }
    return { anchors: anchorMap, covered: coveredSet };
  }, [rects]);
  const rowTop = useMemo(() => {
    let y = 0;
    return [...rows.map((row) => { const top = y; y += row.height; return top; }), y];
  }, [rows]);
  const colLeft = useMemo(() => {
    let x = 0;
    return [...doc.columns.map((column) => { const left = x; x += column.width ?? DEFAULT_COLUMN_WIDTH; return left; }), x];
  }, [doc.columns]);
  const totalHeight = at(rowTop, rows.length);
  const totalWidth = at(colLeft, doc.columns.length);

  // eslint-disable-next-line react-hooks/incompatible-library -- the compiler skips this component; nothing here is memoized by hand
  const rowVirtual = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: (i) => at(rows, i).height,
    overscan: 6,
    paddingStart: LABEL_BAND_HEIGHT,
    scrollPaddingStart: LABEL_BAND_HEIGHT,
    initialRect: { width: 800, height: 400 },
  });
  const columnVirtual = useVirtualizer({
    horizontal: true,
    count: doc.columns.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: (j) => at(doc.columns, j).width ?? DEFAULT_COLUMN_WIDTH,
    overscan: 2,
    initialRect: { width: 800, height: 400 },
  });

  // The rows and columns to draw: those on the screen, the anchor of every merged area that reaches into them, and the
  // active cell (so that the keyboard never loses it).
  const visibleRows = (() => {
    const set = new Set(rowVirtual.getVirtualItems().map((item) => item.index));
    const columnsShown = columnVirtual.getVirtualItems().map((item) => item.index);
    if (set.size > 0 && columnsShown.length > 0) {
      const [rMin, rMax] = [Math.min(...set), Math.max(...set)];
      const [cMin, cMax] = [Math.min(...columnsShown), Math.max(...columnsShown)];
      for (const m of rects) {
        if (m.r0 <= rMax && m.r1 >= rMin && m.c0 <= cMax && m.c1 >= cMin) {
          set.add(m.r0);
        }
      }
    }
    set.add(selection.fr);
    return [...set].filter((i) => i < rows.length).sort((a, b) => a - b);
  })();
  const visibleColumns = (() => {
    const set = new Set(columnVirtual.getVirtualItems().map((item) => item.index));
    const rowsShown = rowVirtual.getVirtualItems().map((item) => item.index);
    if (set.size > 0 && rowsShown.length > 0) {
      const [cMin, cMax] = [Math.min(...set), Math.max(...set)];
      const [rMin, rMax] = [Math.min(...rowsShown), Math.max(...rowsShown)];
      for (const m of rects) {
        if (m.r0 <= rMax && m.r1 >= rMin && m.c0 <= cMax && m.c1 >= cMin) {
          set.add(m.c0);
        }
      }
    }
    set.add(selection.fc);
    return [...set].filter((j) => j < doc.columns.length).sort((a, b) => a - b);
  })();

  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus();
      if (editing.initial === "") {
        inputRef.current.select();
      }
    }
  }, [editing]);

  // Annotate mode: the panel can stand the selection on a place (a comment "shown in the grid"); bring it into view.
  const annotating = annotation !== undefined;
  useEffect(() => {
    if (annotating) {
      rowVirtual.scrollToIndex(selection.fr);
      columnVirtual.scrollToIndex(selection.fc);
    }
    // The virtualizers are stable objects; only the place the selection stands on should run this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [annotating, selection.fr, selection.fc]);

  useEffect(() => {
    const up = () => {
      dragging.current = false;
    };
    window.addEventListener("mouseup", up);
    return () => window.removeEventListener("mouseup", up);
  }, []);

  const lastRow = rows.length - 1;
  const lastColumn = doc.columns.length - 1;
  const cellId = (r: number, c: number) => `${gridId}-${r}-${c}`;

  /** The anchor of the merged area that holds the position, or the position itself. */
  const anchorOf = (r: number, c: number): [number, number] => {
    if (!covered.has(`${r}:${c}`)) {
      return [r, c];
    }
    const hit = rects.find((m) => contains(m, r, c));
    return hit ? [hit.r0, hit.c0] : [r, c];
  };

  const moveTo = (row: number, column: number, extend: boolean) => {
    const [r, c] = anchorOf(Math.max(0, Math.min(lastRow, row)), Math.max(0, Math.min(lastColumn, column)));
    editor.select(extend ? { ...selection, fr: r, fc: c } : { ar: r, ac: c, fr: r, fc: c });
    rowVirtual.scrollToIndex(r);
    columnVirtual.scrollToIndex(c);
  };

  const startEdit = (row: number, column: number, seed?: string) => {
    if (readOnly) {
      // Annotate mode: opening a cell is asking for the box of a note on it; anywhere else a read-only grid does nothing.
      annotation?.onActivate();
      return;
    }
    const target = rows[row];
    if (!target || target.zone === "blank") {
      editor.say("The blank rows show what a filler will get. They cannot be changed.", true);
      return;
    }
    if (canEditCell && !canEditCell(target.id, at(doc.columns, column).id)) {
      editor.say("This cell is fixed text and cannot be typed into.", true);
      return;
    }
    const cell = doc.cells[cellKey(target.id, at(doc.columns, column).id)];
    // Typing into a cell replaces its text; opening it (Enter, F2, double click) edits the text that is there.
    flushSync(() => setEditing({ row, column, initial: seed ?? shown(cell?.value) }));
  };

  const finishEdit = (save: boolean, dr = 0, dc = 0) => {
    const current = editing;
    if (!current) {
      return;
    }
    if (save) {
      const target = at(rows, current.row);
      const text = inputRef.current?.value ?? "";
      const problem = editor.setText(target.id, at(doc.columns, current.column).id, text);
      if (problem) {
        // Stay in the cell: the person keeps what they typed and is told why it was refused.
        return;
      }
    }
    setEditing(null);
    scrollRef.current?.focus();
    if (dr !== 0 || dc !== 0) {
      moveTo(current.row + dr, current.column + dc, false);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.target !== scrollRef.current || editing) {
      return;
    }
    if (event.nativeEvent.isComposing) {
      return;
    }
    const extend = event.shiftKey;
    const modifier = event.ctrlKey || event.metaKey;
    const anchor = anchors.get(`${selection.fr}:${selection.fc}`);
    const down = selection.fr + (anchor ? anchor.r1 - anchor.r0 + 1 : 1);
    const right = selection.fc + (anchor ? anchor.c1 - anchor.c0 + 1 : 1);
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        return moveTo(down, selection.fc, extend);
      case "ArrowUp":
        event.preventDefault();
        return moveTo(selection.fr - 1, selection.fc, extend);
      case "ArrowRight":
        event.preventDefault();
        return moveTo(selection.fr, right, extend);
      case "ArrowLeft":
        event.preventDefault();
        return moveTo(selection.fr, selection.fc - 1, extend);
      case "Tab":
        // The grid is one tab stop; Tab moves inside it until the edge, where it leaves the way it came.
        if ((event.shiftKey && selection.fc === 0 && selection.fr === 0) || (!event.shiftKey && selection.fc === lastColumn && selection.fr === lastRow)) {
          return;
        }
        event.preventDefault();
        return moveTo(selection.fr, selection.fc + (event.shiftKey ? -1 : 1), false);
      case "Home":
        event.preventDefault();
        return moveTo(modifier ? 0 : selection.fr, 0, extend);
      case "End":
        event.preventDefault();
        return moveTo(modifier ? lastRow : selection.fr, lastColumn, extend);
      case "PageDown":
        event.preventDefault();
        return moveTo(selection.fr + 10, selection.fc, extend);
      case "PageUp":
        event.preventDefault();
        return moveTo(selection.fr - 10, selection.fc, extend);
      case "Enter":
      case "F2":
        event.preventDefault();
        return startEdit(selection.fr, selection.fc);
      case "Escape":
        return;
      case "Delete":
      case "Backspace":
        event.preventDefault();
        if (!readOnly) {
          editor.clear(rect);
        }
        return;
      case "Process":
        // An input method editor is composing: its first key opens the cell, and the rest goes to the box.
        if (!readOnly) {
          startEdit(selection.fr, selection.fc, "");
        }
        return;
      default:
    }
    const key = event.key.toLowerCase();
    if (modifier && key === "a") {
      event.preventDefault();
      editor.select({ ar: 0, ac: 0, fr: lastRow, fc: lastColumn });
      return;
    }
    if (readOnly) {
      return;
    }
    if (modifier && key === "z") {
      event.preventDefault();
      return event.shiftKey ? editor.redo() : editor.undo();
    }
    if (modifier && key === "y") {
      event.preventDefault();
      return editor.redo();
    }
    if (modifier && key === "b") {
      event.preventDefault();
      return editor.bold();
    }
    if (modifier && key === "i") {
      event.preventDefault();
      return editor.italic();
    }
    if (!modifier && !event.altKey && event.key.length === 1) {
      event.preventDefault();
      startEdit(selection.fr, selection.fc, event.key);
    }
  };

  const onPaste = (event: ClipboardEvent<HTMLDivElement>) => {
    if (event.target !== scrollRef.current || readOnly) {
      return;
    }
    event.preventDefault();
    editor.paste(rect.r0, rect.c0, parseClipboard(event.clipboardData.getData("text/plain")));
  };

  const onCopy = (event: ClipboardEvent<HTMLDivElement>) => {
    if (event.target !== scrollRef.current) {
      return;
    }
    event.preventDefault();
    const lines: string[] = [];
    for (let r = rect.r0; r <= rect.r1; r++) {
      const line: string[] = [];
      for (let c = rect.c0; c <= rect.c1; c++) {
        const row = at(rows, r);
        line.push(row ? shown(doc.cells[cellKey(row.id, at(doc.columns, c).id)]?.value) : "");
      }
      lines.push(line.join("\t"));
    }
    event.clipboardData.setData("text/plain", lines.join("\n"));
  };

  const positionOf = (event: MouseEvent<HTMLDivElement>): { r: number; c: number } | null => {
    const element = (event.target as HTMLElement).closest<HTMLElement>("[data-r]");
    return element ? { r: Number(element.dataset.r), c: Number(element.dataset.c) } : null;
  };

  const onMouseDown = (event: MouseEvent<HTMLDivElement>) => {
    const at = positionOf(event);
    if (!at || editing) {
      return;
    }
    dragging.current = true;
    editor.select(event.shiftKey ? { ...selection, fr: at.r, fc: at.c } : { ar: at.r, ac: at.c, fr: at.r, fc: at.c });
    scrollRef.current?.focus();
    event.preventDefault();
  };

  const onMouseOver = (event: MouseEvent<HTMLDivElement>) => {
    const at = dragging.current ? positionOf(event) : null;
    if (at) {
      editor.select({ ...selection, fr: at.r, fc: at.c });
    }
  };

  const onPointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    // A touch screen sends no double click: two taps close together open the cell.
    if (event.pointerType !== "touch") {
      return;
    }
    const element = (event.target as HTMLElement).closest<HTMLElement>("[data-r]");
    if (!element) {
      return;
    }
    const now = Date.now();
    if (now - lastTap.current < 350) {
      startEdit(Number(element.dataset.r), Number(element.dataset.c));
    }
    lastTap.current = now;
  };

  const activeId = rows.length > 0 && doc.columns.length > 0 ? cellId(selection.fr, selection.fc) : undefined;

  return (
    <div className="sheet-grid-wrap">
      <div
        ref={scrollRef}
        className="sheet-grid"
        role="grid"
        tabIndex={0}
        aria-label={label}
        aria-rowcount={rows.length + 1}
        aria-colcount={doc.columns.length}
        aria-multiselectable="true"
        aria-readonly={readOnly}
        aria-activedescendant={activeId}
        onKeyDown={onKeyDown}
        onPaste={onPaste}
        onCopy={onCopy}
        onMouseDown={onMouseDown}
        onMouseOver={onMouseOver}
        onPointerUp={onPointerUp}
        onCompositionStart={() => {
          if (!editing && !readOnly) {
            startEdit(selection.fr, selection.fc, "");
          }
        }}
        onDoubleClick={(event) => {
          const element = (event.target as HTMLElement).closest<HTMLElement>("[data-r]");
          if (element) {
            startEdit(Number(element.dataset.r), Number(element.dataset.c));
          }
        }}
      >
        <div className="sheet-canvas" style={{ width: totalWidth, height: LABEL_BAND_HEIGHT + totalHeight }}>
          <div role="row" aria-rowindex={1} className="sheet-labels" style={{ height: LABEL_BAND_HEIGHT, width: totalWidth }}>
            {visibleColumns.map((j) => {
              const column = at(doc.columns, j);
              const columnNotes = annotation?.marks.columns.get(column.id);
              return (
                <div
                  key={column.id}
                  role="columnheader"
                  aria-colindex={j + 1}
                  className={["sheet-label", annotation ? "sheet-label-pick" : "", columnNotes ? "sheet-noted" : ""]
                    .filter(Boolean)
                    .join(" ")}
                  style={{ left: at(colLeft, j), width: column.width ?? DEFAULT_COLUMN_WIDTH, height: LABEL_BAND_HEIGHT }}
                  onMouseDown={
                    annotation
                      ? (event) => {
                          // A click on a column label stands on that column, so that a note can be written about it.
                          event.preventDefault();
                          moveTo(selection.fr, j, false);
                          scrollRef.current?.focus();
                        }
                      : undefined
                  }
                >
                  {column.label}
                  {column.required ? <span aria-hidden="true"> *</span> : null}
                  {column.required ? <span className="visually-hidden"> (required)</span> : null}
                  {columnNotes ? <span className="visually-hidden"> ({notesWords(columnNotes)})</span> : null}
                </div>
              );
            })}
          </div>
          {visibleRows.map((i) => {
            const row = at(rows, i);
            const rowNotes = annotation?.marks.rows.get(row.id);
            const rowName = [row.label || "", rowNotes ? notesWords(rowNotes) : ""].filter(Boolean).join(", ");
            return (
              <div
                key={row.id}
                role="row"
                aria-rowindex={i + 2}
                aria-label={rowName || undefined}
                className={rowNotes ? "sheet-row sheet-row-noted" : "sheet-row"}
                style={{ top: LABEL_BAND_HEIGHT + at(rowTop, i), height: row.height, width: totalWidth }}
              >
                {visibleColumns.map((j) => {
                  if (covered.has(`${i}:${j}`)) {
                    return null;
                  }
                  const column = at(doc.columns, j);
                  const key = cellKey(row.id, column.id);
                  const cell = doc.cells[key];
                  const area = anchors.get(`${i}:${j}`);
                  const width = (area ? at(colLeft, area.c1 + 1) : at(colLeft, j + 1)) - at(colLeft, j);
                  const height = (area ? at(rowTop, area.r1 + 1) : at(rowTop, i + 1)) - at(rowTop, i);
                  const fixed = cellKind(row.zone, cell) === "fixed";
                  const cellNotes = annotation?.marks.cells.get(key);
                  const inRect = i >= rect.r0 && i <= rect.r1 && j >= rect.c0 && j <= rect.c1;
                  const active = i === selection.fr && j === selection.fc;
                  const classes = [
                    "sheet-cell",
                    fixed ? "sheet-cell-fixed" : "sheet-cell-input",
                    row.zone === "header" ? "sheet-cell-header" : "",
                    row.zone === "blank" ? "sheet-cell-blank" : "",
                    cell?.style?.bold ? "sheet-bold" : "",
                    cell?.style?.italic ? "sheet-italic" : "",
                    SIZE_CLASS[cell?.style?.fontSize ?? "NORMAL"],
                    inRect ? "sheet-selected" : "",
                    active ? "sheet-active" : "",
                    area ? "sheet-merged" : "",
                    cellNotes ? "sheet-noted" : "",
                  ]
                    .filter(Boolean)
                    .join(" ");
                  const editingHere = editing !== null && editing.row === i && editing.column === j;
                  const text = shown(cell?.value);
                  return (
                    <div
                      key={column.id}
                      id={cellId(i, j)}
                      role="gridcell"
                      data-r={i}
                      data-c={j}
                      className={classes}
                      aria-colindex={j + 1}
                      aria-selected={inRect}
                      aria-readonly={readOnly || row.zone === "blank"}
                      aria-rowspan={area ? area.r1 - area.r0 + 1 : undefined}
                      aria-colspan={area ? area.c1 - area.c0 + 1 : undefined}
                      style={{ left: at(colLeft, j), width, height }}
                    >
                      {editingHere ? (
                        <input
                          ref={inputRef}
                          type="text"
                          className="sheet-input"
                          autoComplete="off"
                          spellCheck={false}
                          aria-label={`${column.label}, ${row.label || `row ${i + 1}`}`}
                          defaultValue={editing.initial}
                          onKeyDown={(event) => {
                            if (event.nativeEvent.isComposing) {
                              return;
                            }
                            if (event.key === "Enter") {
                              event.preventDefault();
                              finishEdit(true, event.shiftKey ? -1 : 1, 0);
                            } else if (event.key === "Tab") {
                              event.preventDefault();
                              finishEdit(true, 0, event.shiftKey ? -1 : 1);
                            } else if (event.key === "Escape") {
                              event.preventDefault();
                              finishEdit(false);
                            }
                          }}
                          onBlur={(event) => {
                            if (event.relatedTarget === scrollRef.current) {
                              return;
                            }
                            finishEdit(true);
                          }}
                        />
                      ) : (
                        <>
                          {text}
                          {text === "" && row.zone !== "header" && !fixed ? <span className="visually-hidden">Input cell</span> : null}
                          {cellNotes ? <span className="visually-hidden"> ({notesWords(cellNotes)})</span> : null}
                        </>
                      )}
                    </div>
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
      <p
        className={editor.message?.error ? "form-message sheet-message" : "hint sheet-message"}
        role={editor.message?.error ? "alert" : "status"}
      >
        {editor.message?.text ?? ""}
      </p>
    </div>
  );
}
