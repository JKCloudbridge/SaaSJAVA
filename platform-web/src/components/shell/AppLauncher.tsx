"use client";

import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { GRID_HREF } from "@/lib/shell/areas";

/**
 * The three lines at the left of the strip (Sprint 18, ADR-0128; since FA9, ADR-0203, a link): it opens the grid of
 * applications, the same page the member lands on, from any page. There is one grid, not a grid and a popup, so the
 * list has one look and one place to be kept right. The application that is open is passed along as `?from=` so the
 * grid can mark it; the grid trusts nothing in it (a name that is not in the member's list marks nothing).
 *
 * @param current the API name of the application that is open, if one is
 */
export function AppLauncher({ current }: { current?: string }) {
  const href = current ? `${GRID_HREF}?from=${encodeURIComponent(current)}` : GRID_HREF;
  return (
    <div className="launcher">
      <Link href={href} className="launcher-button" aria-label="Applications" title="All applications">
        <Icon name="menu" className="launcher-icon" />
      </Link>
    </div>
  );
}
