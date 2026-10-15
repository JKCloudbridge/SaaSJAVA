"use client";

import Link from "next/link";
import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { AppIcon } from "@/components/ui/AppIcon";
import { Icon } from "@/components/ui/Icon";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { applicationHref } from "@/lib/applications/entries";

/**
 * The application launcher (Sprint 18, ADR-0128): a button in the control bar that opens a grid of the member's own
 * applications, exactly as the API lists them. Choosing one opens it on the same organization and address; it is not the
 * organization switcher. It lists nothing the API did not (the browser hardcodes no application), shows an honest
 * message while loading, when the list could not be had, and when there is nothing to open, and works from the keyboard:
 * it opens with Enter or Space, moves between applications with the arrow keys, and closes with Escape, returning
 * focus to the button.
 *
 * @param current the API name of the application that is open, marked in the grid
 * @param setupHref where the member can set applications up, if they may be offered the way
 */
export function AppLauncher({ current, setupHref }: { current?: string; setupHref: string | null }) {
  const { state, refresh } = useApplications();
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement | null>(null);
  const panel = useRef<HTMLDivElement | null>(null);
  const panelId = useId();

  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) {
      button.current?.focus();
    }
  }, []);

  useEffect(() => {
    if (!open) {
      return;
    }
    function onPointer(event: PointerEvent) {
      const target = event.target as Node;
      if (!panel.current?.contains(target) && !button.current?.contains(target)) {
        close(false);
      }
    }
    function onKey(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        close(true);
      }
    }
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    panel.current?.querySelector<HTMLElement>("a, button")?.focus();
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, close]);

  function onGridKey(event: KeyboardEvent<HTMLUListElement>) {
    const keys = ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Home", "End"];
    if (!keys.includes(event.key)) {
      return;
    }
    const tiles = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("a"));
    const at = tiles.indexOf(document.activeElement as HTMLElement);
    const columns = 2;
    const next =
      event.key === "Home" ? 0
      : event.key === "End" ? tiles.length - 1
      : event.key === "ArrowRight" ? at + 1
      : event.key === "ArrowLeft" ? at - 1
      : event.key === "ArrowDown" ? at + columns
      : at - columns;
    if (next >= 0 && next < tiles.length) {
      event.preventDefault();
      tiles[next]?.focus();
    }
  }

  const applications = state.applications;
  return (
    <div className="launcher">
      <button
        ref={button}
        type="button"
        className="launcher-button"
        aria-label="Applications"
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <Icon name={open ? "close" : "menu"} className="launcher-icon" />
      </button>
      {open ? (
        <div ref={panel} id={panelId} className="launcher-panel" role="dialog" aria-label="Applications">
          <p className="launcher-label">Applications</p>
          {applications.length > 0 ? (
            <ul className="launcher-grid" onKeyDown={onGridKey}>
              {applications.map((application) => (
                <li key={application.apiName}>
                  <Link
                    href={applicationHref(application)}
                    className="launcher-tile"
                    aria-current={application.apiName === current ? "page" : undefined}
                    onClick={() => close(false)}
                  >
                    <AppIcon iconKey={application.iconKey} label={application.label} />
                    <span className="launcher-tile-name">{application.label}</span>
                  </Link>
                </li>
              ))}
            </ul>
          ) : state.status === "idle" || state.status === "loading" ? (
            <p className="launcher-note" role="status">
              Loading your applications…
            </p>
          ) : state.status === "error" ? (
            <div className="launcher-note" role="alert">
              <p>{state.message}</p>
              <button type="button" className="link-button" onClick={() => void refresh()}>
                Try again
              </button>
            </div>
          ) : (
            <p className="launcher-note" role="status">
              No applications are set up for you yet.
            </p>
          )}
          {applications.length > 0 && state.status === "error" ? (
            <p className="launcher-note" role="alert">
              {state.message} <button type="button" className="link-button" onClick={() => void refresh()}>Try again</button>
            </p>
          ) : null}
          {setupHref ? (
            <Link href="/setup/applications" className="launcher-footer" onClick={() => close(false)}>
              <Icon name="setup" className="launcher-icon" />
              Manage applications
            </Link>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
