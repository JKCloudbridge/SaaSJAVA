"use client";

import { useMemo, type CSSProperties } from "react";
import { Modal } from "@/components/ui/Modal";
import { fromApi } from "@/lib/sheets/adapter";
import { ALIGN_CLASS, COLOUR_CLASS, printModel, SIZE_CLASS, type PrintCell } from "@/lib/sheets/print";
import type { RegisterView } from "./register";

const MM_PER_INCH = 25.4;

/** The class a cell gets from its style: the same fixed classes as the print view of a sheet. */
function classesOf(cell: PrintCell): string {
  const style = cell.style;
  return [
    "sheet-print-cell",
    cell.header ? "sheet-print-header" : "",
    style.bold ? "sheet-bold" : "",
    style.italic ? "sheet-italic" : "",
    style.underline ? "sheet-underline" : "",
    ALIGN_CLASS[style.alignment ?? "LEFT"],
    COLOUR_CLASS[style.colour ?? "DEFAULT"],
    SIZE_CLASS[style.fontSize ?? "NORMAL"],
  ]
    .filter(Boolean)
    .join(" ");
}

/** A margin as a share of the width of the page, so that it scales with the page on a narrow screen. */
const share = (inches: number, pageWidthMm: number): string => `${((inches * MM_PER_INCH) / pageWidthMm) * 100}%`;

/**
 * The register as it would appear in the print preview of a spreadsheet (Functional track FA10, ADR-0204): a white page the
 * shape of the paper the Excel file is set for, with that file's margins, the merged boxes and borders of the register, no
 * grid lines and no headings, fitted to the width of the page. It is a reading: nothing can be typed or saved here.
 *
 * <p>It draws the server's own drawing of the stored values (`grid`) with the server's own page setup (`print`, the
 * description the file is written from), so the view, the Excel page and the file can never disagree; the browser works
 * out no layout of its own.
 */
export function RegisterPrintView({ view, onClose }: { view: RegisterView; onClose: () => void }) {
  const model = useMemo(() => printModel(fromApi(view.grid), { labels: false }), [view.grid]);
  const { print } = view;
  const paper = {
    "--register-paper-width": `${print.pageWidthMm}mm`,
    "--register-paper-ratio": `${print.pageWidthMm} / ${print.pageHeightMm}`,
  } as CSSProperties;
  const margins: CSSProperties = {
    paddingLeft: share(print.marginLeft, print.pageWidthMm),
    paddingRight: share(print.marginRight, print.pageWidthMm),
    paddingTop: share(print.marginTop, print.pageWidthMm),
    paddingBottom: share(print.marginBottom, print.pageWidthMm),
  };
  return (
    <Modal title="Register" onClose={onClose} testId="register-print-popup" wide>
      <div className="stack">
        <p className="hint" data-testid="register-print-note">
          {print.paper}, {print.orientation}. This is how the register looks when it is printed.
        </p>
        <div className="register-paper-scroll">
          <div
            className="sheet-print-page register-paper"
            style={paper}
            data-testid="register-paper"
            data-paper={print.paper}
            data-orientation={print.orientation}
          >
            <div style={margins} data-testid="register-paper-margins">
              <table className="sheet-print-table" aria-label="The register as it is printed">
                <colgroup>
                  {model.columns.map((column) => (
                    <col key={column.id} style={{ width: `${column.percent}%` } as CSSProperties} />
                  ))}
                </colgroup>
                <tbody>
                  {model.body.map((row) => (
                    <tr key={row.id} style={{ height: row.height }}>
                      {row.cells.map((cell) => (
                        <td key={cell.key} rowSpan={cell.rowSpan} colSpan={cell.colSpan} className={classesOf(cell)}>
                          {cell.text}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
        <div>
          <button type="button" className="button button-secondary" onClick={onClose} data-testid="register-print-close">
            Close
          </button>
        </div>
      </div>
    </Modal>
  );
}
