import type { SolutionPages } from "../types";
import { ChangeRequestsPage } from "./ChangeRequestsPage";
import { QualityHomePage } from "./home/QualityHome";
import { FillInReportPage } from "./FillInReportPage";
import { FillInsPage } from "./FillInsPage";
import { TemplatesPage } from "./TemplatesPage";

/**
 * The page keys an entry of the Quality Management System application may name (the bundle that ships the application lists
 * them in `solution.yml`). A key that is not here shows the plain "not available" page.
 */
export const PAGE_KEYS = ["home", "templates", "fill-ins", "change-requests", "fill-in-report"] as const;

export const inspection: SolutionPages = {
  application: "inspection",
  pages: {
    home: QualityHomePage,
    templates: TemplatesPage,
    "fill-ins": FillInsPage,
    "change-requests": ChangeRequestsPage,
    "fill-in-report": FillInReportPage,
  },
};
