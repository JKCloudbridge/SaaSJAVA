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
