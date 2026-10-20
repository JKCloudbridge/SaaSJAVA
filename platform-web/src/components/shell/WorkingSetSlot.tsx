"use client";

import { usePathname } from "next/navigation";
import { Suspense } from "react";
import { WorkingSetBar } from "@/components/setup/WorkingSetBar";

/** The pages where a change can be made, so the person can choose where it goes: live at once or into a change set. */
const WORKING_SET_PAGES = [
  "/setup/objects",
  "/setup/applications",
  "/setup/tabs",
  "/setup/workflows",
  "/setup/approvals",
  "/setup/notification-text",
  "/setup/changes",
];

/** Whether the working-set selector shows on this page. */
export function showsWorkingSet(pathname: string): boolean {
  return WORKING_SET_PAGES.some((page) => pathname === page || pathname.startsWith(`${page}/`));
}

/**
 * The change-set selector at the top of the content (Sprint 11, Sprint 21), on every page where it showed before UA1.
 * It used to sit inside the setup frame; the frame is gone and the selector is placed by the address instead, so no
 * page had to be touched to keep it.
 */
export function WorkingSetSlot() {
  const pathname = usePathname() ?? "";
  if (!showsWorkingSet(pathname)) {
    return null;
  }
  // The bar reads the section of the address, which needs a Suspense boundary on a statically built page.
  return (
    <Suspense fallback={null}>
      <WorkingSetBar />
    </Suspense>
  );
}
