"use client";

import type { Alignment, TextColour } from "@/lib/sheets/types";

/** The same text-style choices in the template and fill-in editors. */
export function TextStyleControls({ underline, alignment, colour, disabled = false, reason,
  onUnderline, onAlignment, onColour }: {
  underline: boolean; alignment: Alignment | null; colour: TextColour | null; disabled?: boolean; reason?: string;
  onUnderline(): void; onAlignment(value: Alignment): void; onColour(value: TextColour): void;
}) {
  return <>
    <button type="button" className="button button-secondary" aria-label="Underline" aria-pressed={underline}
      disabled={disabled} title={disabled ? reason : "Underline"} onClick={onUnderline}>
      <span className="sheet-underline" aria-hidden="true">U</span>
    </button>
    <label className="sheet-toolbar-field"><span className="visually-hidden">Alignment</span>
      <select aria-label="Alignment" value={alignment ?? ""} disabled={disabled} title={disabled ? reason : "Alignment"}
        onChange={(event) => { if (event.target.value) onAlignment(event.target.value as Alignment); }}>
        {alignment === null ? <option value="">Mixed</option> : null}
        <option value="LEFT">Left</option><option value="CENTER">Centre</option><option value="RIGHT">Right</option>
      </select>
    </label>
    <label className="sheet-toolbar-field"><span className="visually-hidden">Text colour</span>
      <select aria-label="Text colour" value={colour ?? ""} disabled={disabled} title={disabled ? reason : "Text colour"}
        onChange={(event) => { if (event.target.value) onColour(event.target.value as TextColour); }}>
        {colour === null ? <option value="">Mixed</option> : null}
        <option value="DEFAULT">Default</option><option value="ACCENT">Accent</option>
        <option value="SUCCESS">Green</option><option value="DANGER">Red</option>
      </select>
    </label>
  </>;
}
