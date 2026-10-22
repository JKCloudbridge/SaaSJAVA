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
  onEditor,
}: {
  initial?: GridDoc;
  readOnly?: boolean;
  toolbar?: boolean;
  limits?: SheetLimits;
  onEditor?: (editor: SheetEditor) => void;
}) {
  const editor = useSheetEditor(initial, limits);
  useEffect(() => {
    onEditor?.(editor);
  });
  return toolbar ? (
    <GridView editor={editor} readOnly={readOnly} label="Test sheet" />
  ) : (
    <SheetGrid editor={editor} readOnly={readOnly} label="Test sheet" />
  );
}
