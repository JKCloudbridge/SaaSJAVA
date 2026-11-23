"use client";

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";

/**
 * A popup over the page that asks for something (a new record, a choice). It holds the focus inside while it is open,
 * moves it to the first field, closes on Escape and gives it back to what opened it. A click beside it does not close it:
 * a person who typed something is never thrown away by a stray click. It draws what it is given and decides nothing.
 *
 * <p>{@code launcher} is the window of the application grid (and only that): it has no cross, a click on the dim area
 * beside it closes it (Escape still does), its scroll bar is not drawn and a small arrow at the bottom says there is more
 * to scroll to. Nobody types anything in it, so a stray click loses nothing.
 */
export function Modal({
  title,
  onClose,
  children,
  testId,
  wide = false,
  launcher = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  testId?: string;
  wide?: boolean;
  launcher?: boolean;
}) {
  const heading = useId();
  const box = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const [more, setMore] = useState(false);

  // The arrow shows while there is more below the visible part; it goes when the end is reached.
  const measure = useCallback(() => {
    const element = body.current;
    setMore(Boolean(element) && element!.scrollHeight - element!.scrollTop - element!.clientHeight > 8);
  }, []);

  useEffect(() => {
    if (!launcher) {
      return;
    }
    const element = body.current;
    measure();
    window.addEventListener("resize", measure);
    const observer = typeof ResizeObserver === "undefined" || !element ? undefined : new ResizeObserver(measure);
    if (element && observer) {
      observer.observe(element);
      Array.from(element.children).forEach((child) => observer.observe(child));
    }
    return () => {
      window.removeEventListener("resize", measure);
      observer?.disconnect();
    };
  }, [launcher, measure]);

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
    <div
      className="modal-backdrop"
      data-testid={testId}
      onMouseDown={launcher ? (event) => { if (event.target === event.currentTarget) { onClose(); } } : undefined}
    >
      <div className={wide ? "modal modal-wide" : "modal"} role="dialog" aria-modal="true" aria-labelledby={wide ? undefined : heading} aria-label={wide ? title : undefined} ref={box}>
        {launcher ? null : wide ? (
          <button type="button" className="modal-float-close" aria-label="Close" onClick={onClose}>
            ×
          </button>
        ) : (
          <div className="modal-head">
            <h2 id={heading}>{title}</h2>
            <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
              ×
            </button>
          </div>
        )}
        <div className={launcher ? "modal-body modal-body-quiet" : "modal-body"} ref={body} onScroll={launcher ? measure : undefined}>
          {children}
        </div>
        {launcher && more ? (
          <button
            type="button"
            className="modal-scroll-hint"
            aria-label="Scroll down for more applications"
            onClick={() => body.current?.scrollBy({ top: Math.max(200, body.current.clientHeight * 0.8), behavior: "smooth" })}
          >
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">
              <path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        ) : null}
      </div>
    </div>
  );
}
