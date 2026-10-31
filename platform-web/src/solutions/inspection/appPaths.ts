/**
 * The application the screens of this solution are shown in (FA13A, ADR-0225). The same pages are registered under the
 * Quality Management System and under Document Management, so a page never assumes its own address: it asks which
 * application the address names (`useApplication`). This module holds only the pure parts.
 */
export const DEFAULT_APPLICATION = "inspection";

/** The start of every address inside an application: `/apps/<application>`. */
export function appBase(application: string): string {
  return `/apps/${encodeURIComponent(application)}`;
}
