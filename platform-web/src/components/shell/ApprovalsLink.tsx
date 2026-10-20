"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { Icon } from "@/components/ui/Icon";
import { api } from "@/lib/api/client";
import { useApplications } from "@/lib/applications/ApplicationsProvider";

/**
 * The link to the approvals inbox in the header, beside the bell, with the number of tasks that wait (Sprint 24; moved
 * from the left list in UA1, ADR-0167). The API says whether to show it at all (the plan has approvals, or the member
 * was asked or submitted something) and how many tasks wait; the link is a convenience and the inbox page answers for
 * itself. A failure to ask shows no link and no error: nothing about approvals is lost by it. Only a signed-in member
 * of an organization has it.
 */
export function ApprovalsLink() {
  const { enabled } = useApplications();
  const pathname = usePathname() ?? "";
  const [summary, setSummary] = useState<{ open: number; available: boolean } | undefined>();
  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    let live = true;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/approvals/inbox/summary");
        if (live && data) {
          setSummary({ open: data.data.openTasks, available: data.data.available });
        }
      } catch {
        // No link, no error.
      }
    })();
    return () => {
      live = false;
    };
  }, [enabled, pathname]);
  if (!enabled || !summary?.available) {
    return null;
  }
  return (
    <Link
      href="/approvals"
      className="bell"
      aria-current={pathname.startsWith("/approvals") ? "page" : undefined}
      data-testid="approvals-link"
    >
      <Icon name="clipboard" className="nav-icon" />
      Approvals
      {summary.open > 0 ? (
        <span className="badge" data-testid="approvals-count" aria-label={`${summary.open} waiting`}>
          {summary.open}
        </span>
      ) : null}
    </Link>
  );
}
