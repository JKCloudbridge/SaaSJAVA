"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";

/**
 * A popup over the page that asks for something (a new record, a choice). It holds the focus inside while it is open,
 * moves it to the first field, closes on Escape and gives it back to what opened it. A click beside it does not close it:
 * a person who typed something is never thrown away by a stray click. It draws what it is given and decides nothing.
 */
export function Modal({
  title,
  onClose,
  children,
  testId,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  testId?: string;
}) {
  const heading = useId();
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const first =
      box.current?.querySelector<HTMLElement>(
        ".modal-body input:not([type=hidden]), .modal-body select, .modal-body textarea, .modal-body button",
      ) ?? box.current?.querySelector<HTMLElement>("button");
    first?.focus();
    return () => {
      before?.focus();
    };
  }, []);

  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab" || !box.current) {
        return;
      }
      const items = Array.from(
        box.current.querySelectorAll<HTMLElement>("a[href], button:not([disabled]), input:not([disabled]), select, textarea"),
      ).filter((item) => item.tabIndex >= 0);
      const start = items[0];
      const end = items[items.length - 1];
      if (!start || !end) {
        return;
      }
      if (event.shiftKey && document.activeElement === start) {
        event.preventDefault();
        end.focus();
      } else if (!event.shiftKey && document.activeElement === end) {
        event.preventDefault();
        start.focus();
      }
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [onClose]);

  return (
    <div className="modal-backdrop" data-testid={testId}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={heading} ref={box}>
        <div className="modal-head">
          <h2 id={heading}>{title}</h2>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}
