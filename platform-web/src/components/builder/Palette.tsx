"use client";

import { useId, useState } from "react";
import { startDrag } from "@/lib/builder/dragPayload";

export interface PaletteItem {
  /** Names the chip inside the builder that made it; it is what a drop carries. */
  key: string;
  label: string;
  /** More about the chip, shown as its tooltip. */
  title?: string;
  /** Already placed (or not allowed): shown dimmed and neither dragged nor added. */
  used?: boolean;
  /** Drawn with the accent colour, for the chip that makes something new. */
  emphasis?: boolean;
}

export interface PaletteCategory {
  id: string;
  label: string;
  items: PaletteItem[];
  /** What to say when the category has nothing to offer. */
  empty: string;
}

/**
 * The pane of things a person can add to what they are building: categories, a search, and chips. A chip is a button, so
 * it can be dragged with the mouse or added with the keyboard (Enter or Space), which is the same change of the draft.
 */
export function Palette({
  label,
  categories,
  onPick,
  searchable = false,
}: {
  label: string;
  categories: PaletteCategory[];
  onPick: (item: PaletteItem, category: string) => void;
  searchable?: boolean;
}) {
  const [active, setActive] = useState(categories[0]?.id ?? "");
  const [text, setText] = useState("");
  const searchId = useId();
  const category = categories.find((one) => one.id === active) ?? categories[0];
  const needle = text.trim().toLowerCase();
  const shown = (category?.items ?? []).filter((item) => needle === "" || item.label.toLowerCase().includes(needle));

  return (
    <aside className="palette" aria-label={label} data-testid="palette">
      {categories.length > 1 ? (
        <div className="palette-nav" role="group" aria-label={`${label}: categories`}>
          {categories.map((one) => (
            <button
              key={one.id}
              type="button"
              aria-pressed={one.id === category?.id}
              onClick={() => {
                setActive(one.id);
                setText("");
              }}
            >
              {one.label}
            </button>
          ))}
        </div>
      ) : null}
      {searchable ? (
        <div className="palette-search">
          <label htmlFor={searchId} className="visually-hidden">
            Search {category?.label.toLowerCase()}
          </label>
          <input
            id={searchId}
            type="search"
            placeholder={`Search ${category?.label.toLowerCase() ?? ""}…`}
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
        </div>
      ) : null}
      <div className="palette-grid" data-testid="palette-grid">
        {shown.length === 0 ? (
          <p className="hint palette-empty">{needle ? "Nothing matches." : (category?.empty ?? "")}</p>
        ) : (
          shown.map((item) => (
            // A div with the role of a button: Firefox does not start a drag from a real button element.
            <div
              key={item.key}
              role="button"
              tabIndex={item.used ? -1 : 0}
              aria-disabled={item.used ? true : undefined}
              className={["palette-chip", item.emphasis ? "palette-chip-new" : ""].filter(Boolean).join(" ")}
              draggable={!item.used}
              title={item.used ? `${item.label}: already added` : (item.title ?? "Drag it in, or press Enter to add it")}
              onDragStart={(event) => startDrag(event, { origin: "palette", key: item.key })}
              onClick={() => {
                if (!item.used) {
                  onPick(item, category?.id ?? "");
                }
              }}
              onKeyDown={(event) => {
                if ((event.key === "Enter" || event.key === " ") && !item.used) {
                  event.preventDefault();
                  onPick(item, category?.id ?? "");
                }
              }}
            >
              {item.label}
            </div>
          ))
        )}
      </div>
    </aside>
  );
}
