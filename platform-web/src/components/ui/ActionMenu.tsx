"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Icon, type IconName } from "./Icon";

/** One entry of an action menu. */
export interface ActionMenuItem {
  label: string;
  onSelect: () => void;
  /** A step that cannot be taken back (retire, end a request) is drawn in the warning colour. */
  danger?: boolean;
}

/**
 * A small icon button that opens a short menu: the settings icon of a list, the "more" of a page. It closes on a choice,
 * on Escape and on a click away, and the button says whether it is open. It decides nothing about what is offered.
 */
export function ActionMenu({
  label,
  items,
  icon = "more",
}: {
  label: string;
  items: readonly ActionMenuItem[];
  icon?: IconName;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) {
      return;
    }
    const click = (event: MouseEvent) => {
      if (!box.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", click);
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("mousedown", click);
      document.removeEventListener("keydown", key);
    };
  }, [open]);
  if (items.length === 0) {
    return null;
  }
  return (
    <div className="list-menu" ref={box}>
      <button
        type="button"
        className="icon-button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((value) => !value)}
      >
        <Icon name={icon} className="list-menu-icon" />
      </button>
      {open ? (
        <ul id={id} role="menu" className="list-menu-items">
          {items.map((item) => (
            <li key={item.label} role="none">
              <button
                type="button"
                role="menuitem"
                className={item.danger ? "menu-danger" : undefined}
                onClick={() => {
                  setOpen(false);
                  item.onSelect();
                }}
              >
                {item.label}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
