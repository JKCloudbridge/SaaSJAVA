import { useEffect } from "react";
import { LIMITS, sampleDoc } from "@/lib/sheets/fixtures";
import type { GridDoc, SheetLimits } from "@/lib/sheets/types";
import { GridView } from "./GridView";
import { SheetGrid } from "./SheetGrid";
import type { SheetEditor } from "./useSheetEditor";
import { useSheetEditor } from "./useSheetEditor";

/** What the tests of the sheet components share: a small host that holds an editor and lets a test read its document. */
export function GridHost({
  initial = sampleDoc(),
  readOnly = false,
  toolbar = false,
  limits = LIMITS,
  footer,
  answers,
  kinds,
  onEditor,
  openCell,
}: {
  /** A cell filled through a popup (SH5.1), handed straight to the grid. */
  openCell?: (rowId: string, columnId: string) => boolean;
  initial?: GridDoc;
  readOnly?: boolean;
  toolbar?: boolean;
  limits?: SheetLimits;
  footer?: string;
  /** The answers of formulas by cell key, and their kinds, handed straight to the grid (SH4). */
  answers?: Record<string, string>;
  kinds?: Record<string, string>;
  onEditor?: (editor: SheetEditor) => void;
}) {
  const editor = useSheetEditor(initial, limits);
  useEffect(() => {
    onEditor?.(editor);
  });
  return toolbar ? (
    <GridView editor={editor} readOnly={readOnly} label="Test sheet" footer={footer} />
  ) : (
    <SheetGrid editor={editor} readOnly={readOnly} label="Test sheet" footer={footer} formulaAnswers={answers} formulaKinds={kinds} openCell={openCell} />
  );
}
