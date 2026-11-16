"use client";

import { useEffect, useMemo, useRef, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import type { AnswerMaps } from "@/lib/sheets/look/sheetLook";
import { ALIGN_CLASS, COLOUR_CLASS, printModel, SIZE_CLASS, type PrintCell } from "@/lib/sheets/print";
import type { GridDoc } from "@/lib/sheets/types";

/** The class a cell gets from its style: the same fixed classes as the grid on the screen. */
function classesOf(cell: PrintCell): string {
  const style = cell.style;
  return [
    "sheet-print-cell",
    cell.header ? "sheet-print-header" : "",
    style.bold ? "sheet-bold" : "",
    style.italic ? "sheet-italic" : "",
    style.underline ? "sheet-underline" : "",
    ALIGN_CLASS[cell.look ? cell.look.alignment : (style.alignment ?? "LEFT")],
    COLOUR_CLASS[style.colour ?? "DEFAULT"],
    SIZE_CLASS[style.fontSize ?? "NORMAL"],
    // a text colour named in the style is the cell's own ink: the automatic one is only for a cell that states none
    ...(cell.look ? cell.look.classes.filter((name) => !(style.colour && name.startsWith("sheet-ink-"))) : []),
  ]
    .filter(Boolean)
    .join(" ");
}

/**
 * The view of a sheet (FA7, ADR-0201, changed in SH5.1): the whole sheet at the width it really has, however big it is, in a
 * view that shows a horizontal or a vertical scroll bar only when the sheet does not fit. Merged cells, column widths, row
 * heights, bold, italic, underline, sizes, alignment and text colours are as the sheet has them, the input cells are empty
 * in a template, and one footer row ends it. The Print button opens the browser's print dialog, where the person chooses the
 * paper size and Portrait or Landscape (and the scale), and which also saves a PDF; the sheet is fitted to the width of the
 * page chosen, and the band of column labels and the header rows repeat on every page.
 *
 * It is drawn from the sheet the person already reads and makes no request. It knows no business word: what the footer
 * says is the caller's. While it is open, printing shows only this view (a class on the body hides the rest of the page).
 */
export function SheetPrintView({
  doc,
  footer,
  label,
  showInput = false,
  answers,
  onClose,
}: {
  doc: GridDoc;
  /** A fill-in shows what was typed into the input cells; a template shows them empty (the default). */
  showInput?: boolean;
  /** The answers of the formulas by cell key with their kinds (SH4): written in their number formats and judged by the colour rules. */
  answers?: AnswerMaps;
  /** The text at the right of the footer row; empty leaves the row blank (the row is always there). */
  footer: string;
  /** What the sheet is called for a screen reader. */
  label: string;
  onClose: () => void;
}) {
  const model = useMemo(() => printModel(doc, { showInput, answers }), [doc, showInput, answers]);
  // The width the sheet really has, so the screen shows all of it and scrolls; printing fits it to the page instead.
  const naturalWidth = model.columns.reduce((sum, column) => sum + column.width, 0);
  const printButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.body.classList.add("sheet-printing");
    printButton.current?.focus();
    return () => {
      document.body.classList.remove("sheet-printing");
      before?.focus();
    };
  }, []);

  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      } else if (event.key === "Tab") {
        // Two buttons are all there is to reach: Tab goes round between them.
        const first = printButton.current;
        const last = closeButton.current;
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [onClose]);

  const count = model.columns.length;
  const headRow = (row: (typeof model.head)[number]) => (
    <tr key={row.id} style={{ height: row.height }}>
      {row.cells.map((cell) => (
        <th key={cell.key} scope={row.zone === "labels" ? "col" : undefined} rowSpan={cell.rowSpan}
          colSpan={cell.colSpan} className={classesOf(cell)}
          style={cell.look ? (cell.look.props as CSSProperties) : undefined}>
          {cell.text}
          {cell.look?.words ? <span className="visually-hidden"> ({cell.look.words})</span> : null}
        </th>
      ))}
    </tr>
  );
  const page = (
    <div className="sheet-print-root" role="dialog" aria-modal="true" aria-label={`View of ${label}`} data-testid="sheet-print-view">
      <div className="sheet-print-bar">
        <button type="button" className="button" ref={printButton} onClick={() => window.print()}>
          Print
        </button>
        <button type="button" className="button button-secondary" ref={closeButton} onClick={onClose}>
          Close
        </button>
        <span className="hint">
          The whole sheet is shown; scroll if it is big. In the print window choose the paper size and Portrait or Landscape;
          it also saves a PDF.
          {showInput ? "" : " Cells a person fills in are shown empty."}
        </span>
      </div>
      <div className="sheet-print-page">
        <table
          className={[
            "sheet-print-table",
            model.looked ? "sheet-print-looked" : "",
            model.fitToWidth ? "sheet-print-fit" : "",
          ].filter(Boolean).join(" ")}
          style={{ "--table-px": `${naturalWidth}px` } as CSSProperties}
          aria-label={label}
        >
          <colgroup>
            {model.columns.map((column) => (
              <col key={column.id} style={{ "--col-px": `${column.width}px`, "--col-pct": `${column.percent}%` } as CSSProperties} />
            ))}
          </colgroup>
          {/* A sheet whose header rows do not repeat shows them once, at the top of the first page. */}
          <thead>{model.repeatHeaderRows ? model.head.map(headRow) : null}</thead>
          <tbody>
            {model.repeatHeaderRows ? null : model.head.map(headRow)}
            {model.body.map((row) => (
              <tr key={row.id} style={{ height: row.height }}>
                {row.cells.map((cell) => (
                  <td key={cell.key} rowSpan={cell.rowSpan} colSpan={cell.colSpan} className={classesOf(cell)}
                    style={cell.look ? (cell.look.props as CSSProperties) : undefined}>
                    {cell.text}
                    {cell.look?.words ? <span className="visually-hidden"> ({cell.look.words})</span> : null}
                  </td>
                ))}
              </tr>
            ))}
            {footer.split("\n").map((line, index) => (
              <tr
                key={index}
                className="sheet-print-footer-row"
                data-testid={index === 0 ? "sheet-print-footer" : `sheet-print-footer-${index + 1}`}
              >
                <td colSpan={Math.max(1, count)} className="sheet-print-cell sheet-print-right sheet-print-footer">
                  {line}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
  return createPortal(page, document.body);
}
