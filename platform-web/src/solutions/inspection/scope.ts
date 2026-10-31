/**
 * The application the page that is drawn belongs to (FA13B, ADR-0226). The calls of the template, fill-in and
 * change-request screens name it in a query parameter so the server knows which set of records they mean; the screens
 * themselves do not carry it from call to call. It is set when an application page is drawn
 * (`solutions/index.ts`, `ScopedPage`) and read when a call is made, so a call started on one page names that page's
 * application whatever page is drawn later. The browser holds no application name of its own: the address names it, the
 * server checks it, and an unknown or unavailable one is answered "not found" like an unknown object.
 */
let current = "";

/** Sets the application of the page being drawn. */
export function setScope(application: string): void {
  current = application;
}

/** The application the calls now made belong to. */
export function scope(): string {
  return current;
}

/** The query parameter every call names its application by. */
export function scopeQuery(): { application: string } {
  return { application: current };
}
