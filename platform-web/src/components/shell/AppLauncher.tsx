"use client";

import Link from "next/link";
import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";
import { AppIcon } from "@/components/ui/AppIcon";
import { Icon } from "@/components/ui/Icon";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { applicationHref } from "@/lib/applications/entries";

/** How many columns the grid has for this many tiles: the panel grows wider instead of growing one long column. */
export function launcherColumns(tiles: number): number {
  if (tiles <= 4) {
    return 2;
  }
  return tiles <= 9 ? 3 : 4;
}

/**
 * The application launcher (Sprint 18, ADR-0128): a button in the control bar that opens a grid of the member's own
 * applications, exactly as the API lists them. Choosing one opens it on the same organization and address; it is
 * not the organization switcher. It lists nothing the API did not (the browser hardcodes no application), shows an
 * honest message while loading, when the list could not be had, and when there is nothing to open, and works from the
 * keyboard: it opens with Enter or Space, moves between applications with the arrow keys, and closes with Escape,
 * returning focus to the button.
 *
 * <p>The grid grows with the number of applications (UA1, ADR-0167): two columns for a few, more columns and a wider
 * panel as the list gets longer, and the panel scrolls when it would be taller than the screen. A person who may set
 * things up also gets a tile to start a new application.
 *
 * @param current the API name of the application that is open, marked in the grid
 * @param canSetUp whether the member has an ability the setup pages are for, so may be offered the way to them
 */
export function AppLauncher({ current, canSetUp }: { current?: string; canSetUp: boolean }) {
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

  const applications = state.applications;
  const columns = launcherColumns(applications.length + (canSetUp ? 1 : 0));

  function onGridKey(event: KeyboardEvent<HTMLUListElement>) {
    const keys = ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Home", "End"];
    if (!keys.includes(event.key)) {
      return;
    }
    const tiles = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("a"));
    const at = tiles.indexOf(document.activeElement as HTMLElement);
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
        <div
          ref={panel}
          id={panelId}
          className="launcher-panel"
          role="dialog"
          aria-label="Applications"
          style={{ "--launcher-columns": columns } as CSSProperties}
        >
          <p className="launcher-label">Applications</p>
          {applications.length > 0 || canSetUp ? (
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
              {canSetUp ? (
                <li>
                  <Link href="/setup/applications/new" className="launcher-tile" onClick={() => close(false)}>
                    <span className="app-icon">
                      <Icon name="plus" className="app-icon-glyph" />
                    </span>
                    <span className="launcher-tile-name">New application</span>
                  </Link>
                </li>
              ) : null}
            </ul>
          ) : null}
          {applications.length === 0 ? (
            state.status === "idle" || state.status === "loading" ? (
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
            )
          ) : null}
          {applications.length > 0 && state.status === "error" ? (
            <p className="launcher-note" role="alert">
              {state.message}{" "}
              <button type="button" className="link-button" onClick={() => void refresh()}>
                Try again
              </button>
            </p>
          ) : null}
          {canSetUp ? (
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
