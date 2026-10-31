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

const PAGES = {
  home: QualityHomePage,
  templates: TemplatesPage,
  "fill-ins": FillInsPage,
  "change-requests": ChangeRequestsPage,
  "fill-in-report": FillInReportPage,
};

export const inspection: SolutionPages = {
  application: "inspection",
  pages: PAGES,
};

/**
 * The same pages under a second application name (FA13A, ADR-0225): Document Management shows the same documents, so
 * it is one set of screens registered twice. The pages ask the address which application they are in.
 */
export const documentManagement: SolutionPages = {
  application: "document-management",
  pages: PAGES,
};
