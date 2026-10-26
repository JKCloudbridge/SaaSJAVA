"use client";

import { useMemo } from "react";
import { GridView } from "@/components/sheets/GridView";
import { useSheetEditor } from "@/components/sheets/useSheetEditor";
import { fromApi } from "@/lib/sheets/adapter";
import type { SheetLimits } from "@/lib/sheets/types";

/** Only the grid's reading needs limits; the register is never edited here. */
const LIMITS: SheetLimits = {
  maxBytes: 1_000_000,
  maxRows: 500,
  maxColumns: 100,
  maxHeaderRows: 10,
  maxCellChars: 5000,
  maxLabelChars: 200,
  maxPicklistValues: 100,
  maxMerges: 2000,
};

/**
 * The register drawn as a spreadsheet page (Functional track FA8, ADR-0202): the same values as the form, laid out as the
 * paper register is, with its merged boxes. Read only: a value is typed in the form view, or in Excel and uploaded. The page
 * is the server's own drawing of the stored values, so the two views can never disagree; it is drawn again whenever the
 * server's drawing changes.
 */
export function RegisterExcelView({ grid, label }: { grid: unknown; label: string }) {
  const key = useMemo(() => JSON.stringify(grid), [grid]);
  return <Drawn key={key} grid={grid} label={label} />;
}

function Drawn({ grid, label }: { grid: unknown; label: string }) {
  const initial = useMemo(() => fromApi(grid), [grid]);
  const editor = useSheetEditor(initial, LIMITS);
  return (
    <div data-testid="register-excel">
      <GridView editor={editor} readOnly label={label} />
    </div>
  );
}
