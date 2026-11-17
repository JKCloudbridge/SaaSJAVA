import { useParams } from "next/navigation";
import { useOptionalApplications } from "@/lib/applications/ApplicationsProvider";
import { DEFAULT_APPLICATION } from "./appPaths";

/** What the API calls the application: its label as the member's list gives it, or a plain word when it is not known. */
export function useApplicationLabel(application: string): string {
  const api = useOptionalApplications();
  return api?.state.applications.find((candidate) => candidate.apiName === application)?.label ?? "Home";
}

/**
 * The application the address names, decoded (FA13A, ADR-0225). Outside an application address (a test, a stand-alone
 * render) it is the first application that shows these pages.
 */
export function useApplication(): string {
  const params = useParams<{ application?: string }>();
  const named = params?.application;
  return named ? decodeURIComponent(named) : DEFAULT_APPLICATION;
}

/**
 * The label of the tab that opens a page, as the member's application list gives it (an application names its own tabs in
 * its bundle), or the given words when the list does not know it (a test, a stand-alone render). The heading and the
 * breadcrumb of a page follow the tab, so one page can carry a different name in a different application.
 */
export function useEntryLabel(pageKey: string, fallback: string): string {
  const api = useOptionalApplications();
  const application = useApplication();
  const entry = api?.state.applications
    .find((candidate) => candidate.apiName === application)
    ?.items.find((item) => item.type === "PLATFORM" && item.pageKey === pageKey);
  return entry?.label ?? fallback;
}
