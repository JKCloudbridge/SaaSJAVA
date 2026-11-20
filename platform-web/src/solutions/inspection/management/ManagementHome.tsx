"use client";

import { Option1Home } from "./option1/Option1Home";

/**
 * The Home page of Management Core (MC3, ADR-0262; the plant board of ADR-0266). The page that was first built (Option 2)
 * was removed on 2026-11-20 after the product owner chose the plant board, so this is the one page.
 */
export function ManagementHomePage() {
  return <Option1Home />;
}
