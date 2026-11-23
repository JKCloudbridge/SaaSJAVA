"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { ApplicationGrid, type GridStatus } from "@/components/apps/ApplicationGrid";
import { Icon } from "@/components/ui/Icon";
import { Modal } from "@/components/ui/Modal";
import { useApplications } from "@/lib/applications/ApplicationsProvider";
import { useSession } from "@/lib/session/SessionProvider";
import { canSetUp } from "@/lib/shell/areas";

/**
 * The three lines at the left of the strip (Sprint 18, ADR-0128; the grid since FA9, ADR-0203): they open the grid of
 * applications in a rounded window over the page, with a cross to close it. It is the same grid the member lands on, so
 * the list has one look; it scrolls inside the window and the page behind stays where it was. Choosing an application
 * (the address changes) closes the window; Escape and the cross close it too.
 *
 * @param current the API name of the application that is open, if one is, so the grid can mark it
 */
export function AppLauncher({ current }: { current?: string }) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  const [openedAt, setOpenedAt] = useState(pathname);

  useEffect(() => {
    if (open && pathname !== openedAt) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- the window closes when the address changes
      setOpen(false);
    }
  }, [open, pathname, openedAt]);

  return (
    <div className="launcher">
      <button
        type="button"
        className="launcher-button"
        aria-label="Applications"
        title="All applications"
        aria-haspopup="dialog"
        onClick={() => {
          setOpenedAt(pathname);
          setOpen(true);
        }}
      >
        <Icon name="menu" className="launcher-icon" />
      </button>
      {open ? <LauncherWindow current={current} onClose={() => setOpen(false)} /> : null}
    </div>
  );
}

function LauncherWindow({ current, onClose }: { current?: string; onClose: () => void }) {
  const { state } = useApplications();
  const { state: session } = useSession();
  const abilities = session.status === "signedIn" ? session.user.abilities : [];
  const known = state.applications.length > 0 || state.status === "ready";
  // A list that could not be had is drawn as it stands (nothing, or what was loaded before): no error text.
  const status: GridStatus = { kind: known || state.status === "error" ? "ready" : "loading" };
  return (
    <Modal title="Applications" onClose={onClose} wide launcher testId="launcher-window">
      <ApplicationGrid
        applications={state.applications}
        status={status}
        current={current}
        canSetUp={canSetUp(abilities)}
      />
    </Modal>
  );
}
