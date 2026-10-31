import { createElement, type ComponentType, type ReactElement } from "react";
import { rolesOfApplication } from "@/lib/applications/objectRoles";
import { setScope } from "./inspection/scope";
import { documentManagement, inspection } from "./inspection/registry";
import type { SolutionPages } from "./types";

/** Every solution this release of the web application knows pages for. A solution the API lists but is not here shows no page. */
export const SOLUTIONS: readonly SolutionPages[] = [inspection, documentManagement];

/**
 * The page a `PLATFORM` entry names, or undefined when this release has none: an unknown application, an unknown key
 * and a key that only looks like a registered one (inherited object properties) all end in the plain "not available"
 * page, never in a guess.
 */
export function findPlatformPage(application: string, pageKey: string | null | undefined): ComponentType | undefined {
  if (!pageKey) {
    return undefined;
  }
  // A page is found by the application's own registration first, else by its key among every registered page: an
  // application of a kind that is already registered needs no web code (FA13B, ADR-0226). The server decides whether the
  // application exists and what the member may do in it; an unknown key is still the plain "not available" page.
  const own = SOLUTIONS.find((candidate) => candidate.application === application);
  // Only an application that declares which objects play the parts (the API lists them) is of the kind whose pages these
  // are; any other keeps its own registration or none, so its Home stays the generic overview.
  const ofTheKind = Object.keys(rolesOfApplication(application)).length > 0;
  const solution =
    own && Object.prototype.hasOwnProperty.call(own.pages, pageKey)
      ? own
      : ofTheKind
        ? SOLUTIONS.find((candidate) => Object.prototype.hasOwnProperty.call(candidate.pages, pageKey))
        : undefined;
  return solution ? solution.pages[pageKey] : undefined;
}

/** The page a `PLATFORM` entry names, ready to draw, or undefined when this release has none. */
export function platformPageElement(application: string, pageKey: string | null | undefined): ReactElement | undefined {
  const page = findPlatformPage(application, pageKey);
  return page ? createElement(ScopedPage, { application, page }) : undefined;
}

/**
 * Draws a page of an application after noting which application it is in, so the calls the page makes name it
 * (`inspection/scope.ts`). Set while drawing, before any effect of the page runs.
 */
function ScopedPage({ application, page }: { application: string; page: ComponentType }): ReactElement {
  setScope(application);
  return createElement(page);
}
