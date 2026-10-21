import type { SolutionPages } from "../types";
import { TemplatesPage } from "./TemplatesPage";

/**
 * The page keys an entry of the Audit maintenance application may name (the bundle that ships the application lists
 * them in `solution.yml`). A key that is not here shows the plain "not available" page.
 */
export const PAGE_KEYS = ["templates"] as const;

export const inspection: SolutionPages = {
  application: "inspection",
  pages: { templates: TemplatesPage },
};
