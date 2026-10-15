"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type MyApplicationView = components["schemas"]["MyApplicationView"];

/**
 * The applications of the signed-in member and their navigation, exactly as the API lists them (architecture note 3,
 * section 15: the browser hardcodes none of it). The server has already chosen the applications of the member's profile,
 * resolved the labels and left out what no longer exists; this component only draws the answer. A link stays inside the
 * organization. An object or a record page has no page to open before the data engine exists (Milestone 4), so it is
 * shown as a label and not as a link. When the call fails nothing is drawn: the fixed links of the shell remain.
 */
export function ApplicationMenu() {
  const [applications, setApplications] = useState<MyApplicationView[]>([]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/applications");
        if (!cancelled && data) {
          setApplications(data.data);
        }
      } catch {
        // The menu is a convenience; the shell works without it.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      {applications.map((application) => (
        <li
          key={application.apiName}
          className="nav-group"
          data-testid="application-menu"
          data-icon={application.iconKey}
        >
          <span className="nav-group-label">{application.label}</span>
          <ul className="nested-list" aria-label={application.label}>
            {application.items.map((entry, index) => (
              <li key={`${entry.type}-${entry.label}-${index}`}>
                {entry.type === "HEADING" ? <strong>{entry.label}</strong> : null}
                {entry.type === "LINK" && entry.path ? <Link href={entry.path}>{entry.label}</Link> : null}
                {entry.type === "OBJECT" || entry.type === "RECORD_PAGE" ? <span>{entry.label}</span> : null}
              </li>
            ))}
          </ul>
        </li>
      ))}
    </>
  );
}
