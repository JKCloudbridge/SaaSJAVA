"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Suspense, type ReactNode } from "react";
import { WorkingSetBar } from "./WorkingSetBar";

const SECTIONS = [
  { href: "/setup/profiles", label: "Profiles" },
  { href: "/setup/access-policies", label: "Access policies" },
  { href: "/setup/groups", label: "Groups" },
  { href: "/setup/roles", label: "Roles" },
  { href: "/setup/objects", label: "Objects" },
  { href: "/setup/applications", label: "Applications" },
  { href: "/setup/tabs", label: "Tabs" },
  { href: "/setup/workflows", label: "Workflows" },
  { href: "/workflows/executions", label: "Workflow runs" },
  { href: "/setup/changes", label: "Change sets" },
  { href: "/setup/history", label: "History" },
];

/** The pages where a change can be made, so the person can choose where it goes: live at once or into a change set. */
const OBJECT_PAGES = ["/setup/objects", "/setup/applications", "/setup/tabs", "/setup/workflows", "/setup/changes"];

/**
 * The frame of the setup pages (profiles, access policies, groups, roles, objects, applications, tabs, workflows, the runs of
 * workflows, change sets, history) of the
 * organization the address names. It only navigates: whether the person may open a page is the API's answer, which each page shows in the API's own words.
 *
 * "General" is a marker, not a page: it shows where the organization's branding and general settings will live once a
 * later sprint builds them (a logo needs storage and an endpoint, which are backend work). It has no link and does nothing.
 */
export function SetupFrame({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? "";
  return (
    <div className="split">
      <nav className="rail" aria-label="Setup">
        <p className="rail-label">Setup</p>
        <ul>
          <li>
            <span className="rail-item rail-item-soon" aria-disabled="true" data-testid="setup-general-placeholder">
              General
              <span className="badge">Soon</span>
            </span>
          </li>
          {SECTIONS.map((section) => (
            <li key={section.href}>
              <Link href={section.href} aria-current={pathname.startsWith(section.href) ? "page" : undefined}>
                {section.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <div className="split-content stack">
        {OBJECT_PAGES.some((page) => pathname.startsWith(page)) ? (
          // The bar reads the section of the address, which needs a Suspense boundary on a statically built page.
          <Suspense fallback={null}>
            <WorkingSetBar />
          </Suspense>
        ) : null}
        {children}
      </div>
    </div>
  );
}
