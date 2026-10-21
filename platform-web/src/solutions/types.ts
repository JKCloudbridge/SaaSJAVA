import type { ComponentType } from "react";

/**
 * The pages a platform-shipped solution registers for the entries of its application (Functional track FA1, ADR-0175).
 *
 * A `PLATFORM` entry of the member's menu names a page key; the page that key stands for is code, so it lives here, with
 * the solution. The browser decides nothing with it: the entry itself is listed only when the API says the member may
 * open it, and the page it draws asks the API for everything else, which decides again on every request.
 */
export interface SolutionPages {
  /** The API name of the application the solution ships. */
  readonly application: string;
  /** The page keys the application's entries may name, and the page each one draws. */
  readonly pages: Readonly<Record<string, ComponentType>>;
}
