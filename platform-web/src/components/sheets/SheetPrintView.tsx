"use client";

import { useEffect, useMemo, useRef, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import type { PrintDescription } from "@/lib/sheets/api";
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
 * The print view of a sheet (FA7, ADR-0201): the sheet the way Ctrl+P shows it in a spreadsheet program. Pages are laid out
 * by the browser: the band of column labels and the header rows repeat on every page, merged cells, column widths, row
 * heights, bold, italic, underline, sizes, alignment and text colours are as the sheet has them, the input cells are empty,
 * the sheet is fitted to the width of the page, and one footer row ends it. The Print button opens the browser's print
 * dialog, which also saves a PDF. The paper is A4, landscape when the sheet is wide.
 *
 * It is drawn from the sheet the person already reads and makes no request. It knows no business word: what the footer
 * says is the caller's. While it is open, printing shows only this view (a class on the body hides the rest of the page).
 */
export function SheetPrintView({
  doc,
  print,
  footer,
  label,
  showInput = false,
  answers,
  onClose,
}: {
  doc: GridDoc;
  /**
   * How the page is set up, as the server describes it (SH3): the same description the Excel file is written from, so the
   * view and the file cannot disagree. A sheet whose description is not at hand (a fill-in, until its own release) is
   * drawn with the model's own orientation, as before.
   */
  print?: PrintDescription;
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
  const orientation = print ? print.orientation : model.orientation;
  const paper = print ? print.paper : "A4";
  // A paper other than A4 is the server's own size in millimetres; the A4 widths are the tokens'.
  const pageStyle = print && print.paper !== "A4" ? ({ width: `${print.pageWidthMm}mm` } as CSSProperties) : undefined;
  const printButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  // The paper size is the one thing a style rule cannot get from a class: a fixed sentence of two possible values. It goes in
  // as a stylesheet made by script (a style element would break a strict content policy); a browser that cannot do that
  // (and a test) gets the element below instead.
  const pageRule = `@page { size: ${paper} ${orientation}; margin: 12mm; }`;
  const byScript = typeof CSSStyleSheet !== "undefined" && "adoptedStyleSheets" in document;
  useEffect(() => {
    if (!byScript) return;
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(pageRule);
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
    return () => {
      document.adoptedStyleSheets = document.adoptedStyleSheets.filter((one) => one !== sheet);
    };
  }, [byScript, pageRule]);

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
      {byScript ? null : <style>{pageRule}</style>}
      <div className="sheet-print-bar">
        <button type="button" className="button" ref={printButton} onClick={() => window.print()}>
          Print
        </button>
        <button type="button" className="button button-secondary" ref={closeButton} onClick={onClose}>
          Close
        </button>
        <span className="hint">
          {paper}, {orientation}. The print window also saves a PDF.
          {showInput ? "" : " Cells a person fills in are shown empty."}
        </span>
      </div>
      <div className={`sheet-print-page sheet-print-${orientation}`} style={pageStyle}>
        <table className={model.looked ? "sheet-print-table sheet-print-looked" : "sheet-print-table"} aria-label={label}>
          <colgroup>
            {model.columns.map((column) => (
              <col key={column.id} style={(model.fitToWidth ? { width: `${column.percent}%` } : { width: column.width }) as CSSProperties} />
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
