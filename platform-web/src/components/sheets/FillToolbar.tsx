"use client";

import { cellKey } from "@/lib/sheets/geometry";
import type { FontSize } from "@/lib/sheets/types";
import type { FillEditor } from "./useFillEditor";
import { TextStyleControls } from "./TextStyleControls";

/** The template strip remains recognisable; structure belongs to the published template. */
export function FillToolbar({ editor, readOnly }: { editor: FillEditor; readOnly: boolean }) {
  const row = editor.rows[editor.selection.fr];
  const column = editor.doc.columns[editor.selection.fc];
  const input = Boolean(row && column && row.zone === "body" && editor.canEditCell(row.id, column.id));
  const style = row && column ? editor.doc.cells[cellKey(row.id, column.id)]?.style : undefined;
  const reason = readOnly ? "This fill-in is read-only." : "Select an input cell to format it.";
  const structure = "The published template fixes this structure.";
  const unavailable = readOnly || !input;
  const adds = !readOnly && editor.grid.rowsMode !== "FIXED";
  return <>
    <div className="sheet-toolbar" role="toolbar" aria-label="Fill-in formatting">
      <button type="button" className="button button-secondary" aria-label="Bold" aria-pressed={Boolean(style?.bold)}
        disabled={unavailable} title={unavailable ? reason : "Bold"} onClick={editor.bold}><b>B</b></button>
      <button type="button" className="button button-secondary" aria-label="Italic" aria-pressed={Boolean(style?.italic)}
        disabled={unavailable} title={unavailable ? reason : "Italic"} onClick={editor.italic}><i>I</i></button>
      <label className="sheet-toolbar-field"><span className="visually-hidden">Text size</span>
        <select aria-label="Text size" value={style?.fontSize ?? "NORMAL"} disabled={unavailable}
          title={unavailable ? reason : "Text size"}
          onChange={(event) => editor.format({ fontSize: event.target.value as FontSize })}>
          <option value="SMALL">Small</option><option value="NORMAL">Normal</option>
          <option value="LARGE">Large</option><option value="TITLE">Title</option>
        </select>
      </label>
      <TextStyleControls underline={Boolean(style?.underline)} alignment={style?.alignment ?? "LEFT"}
        colour={style?.colour ?? "DEFAULT"} disabled={unavailable} reason={reason} onUnderline={editor.underline}
        onAlignment={(alignment) => editor.format({ alignment })} onColour={(colour) => editor.format({ colour })} />
      <button type="button" className="button button-secondary" disabled={unavailable}
        title={unavailable ? reason : "Use the template's formatting"} onClick={() => editor.format(null)}>Reset formatting</button>
      {["Merge", "Unmerge", "Fixed text", "Input cell"].map((tool) =>
        <button key={tool} type="button" className="button button-secondary" disabled title={structure}
          aria-describedby="fill-structure-reason">{tool}</button>)}
      <button type="button" className="button button-secondary" disabled={readOnly || !editor.canUndo} onClick={editor.undo}>Undo</button>
      <button type="button" className="button button-secondary" disabled={readOnly || !editor.canRedo} onClick={editor.redo}>Redo</button>
      <button type="button" className="button button-secondary" disabled={!adds}
        title={!adds ? structure : "Add a fill-in row"} onClick={editor.addRow}>Add row</button>
      <button type="button" className="button button-secondary" disabled={!adds || !row || row.zone !== "body" || editor.grid.rows.some((line) => line.id === row.id)}
        title={structure} onClick={() => row && editor.removeRow(row.id)}>Remove row</button>
      {["Add column", "Column width", "Row height"].map((tool) =>
        <button key={tool} type="button" className="button button-secondary" disabled title={structure}
          aria-describedby="fill-structure-reason">{tool}</button>)}
    </div>
    <p id="fill-structure-reason" className="hint">The published template fixes merges, cell kinds, columns and sizes. Only input cells can be formatted.</p>
  </>;
}
