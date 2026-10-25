import type { SolutionPages } from "../types";
import { ChangeRequestsPage } from "./ChangeRequestsPage";
import { FillInReportPage } from "./FillInReportPage";
import { FillInsPage } from "./FillInsPage";
import { TemplatesPage } from "./TemplatesPage";

/**
 * The page keys an entry of the Audit maintenance application may name (the bundle that ships the application lists
 * them in `solution.yml`). A key that is not here shows the plain "not available" page.
 */
export const PAGE_KEYS = ["templates", "fill-ins", "change-requests", "fill-in-report"] as const;

export const inspection: SolutionPages = {
  application: "inspection",
  pages: {
    templates: TemplatesPage,
    "fill-ins": FillInsPage,
    "change-requests": ChangeRequestsPage,
    "fill-in-report": FillInReportPage,
  },
};
