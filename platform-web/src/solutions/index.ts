import { createElement, type ComponentType, type ReactElement } from "react";
import { inspection } from "./inspection/registry";
import type { SolutionPages } from "./types";

/** Every solution this release of the web application knows pages for. A solution the API lists but is not here shows no page. */
export const SOLUTIONS: readonly SolutionPages[] = [inspection];

/**
 * The page a `PLATFORM` entry names, or undefined when this release has none: an unknown application, an unknown key
 * and a key that only looks like a registered one (inherited object properties) all end in the plain "not available"
 * page, never in a guess.
 */
export function findPlatformPage(application: string, pageKey: string | null | undefined): ComponentType | undefined {
  if (!pageKey) {
    return undefined;
  }
  const solution = SOLUTIONS.find((candidate) => candidate.application === application);
  if (!solution || !Object.prototype.hasOwnProperty.call(solution.pages, pageKey)) {
    return undefined;
  }
  return solution.pages[pageKey];
}

/** The page a `PLATFORM` entry names, ready to draw, or undefined when this release has none. */
export function platformPageElement(application: string, pageKey: string | null | undefined): ReactElement | undefined {
  const page = findPlatformPage(application, pageKey);
  return page ? createElement(page) : undefined;
}
