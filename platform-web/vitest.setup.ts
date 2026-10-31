import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";
import type { MyApplication } from "@/lib/applications/entries";
import { rememberApplications } from "@/lib/applications/objectRoles";

/**
 * The applications whose objects the screens of the template, fill-in and change-request kind read from the member's list
 * (FA13B, ADR-0226). A test of those screens runs in the first, the way the address names it; a test of the second set
 * names the second.
 */
export const TEST_APPLICATIONS: MyApplication[] = [
  {
    apiName: "inspection",
    label: "Quality Management System",
    items: [],
    objectRoles: { template: "InspectionTemplate", fill: "InspectionFill", changeRequest: "InspectionChangeRequest" },
  },
  {
    apiName: "document-management",
    label: "Document Management",
    items: [],
    objectRoles: { template: "InspectionTemplate", fill: "InspectionFill", changeRequest: "InspectionChangeRequest" },
  },
  {
    apiName: "environment-management",
    label: "Environment Management",
    items: [],
    objectRoles: {
      template: "EnvironmentTemplate",
      fill: "EnvironmentFill",
      changeRequest: "EnvironmentChangeRequest",
    },
  },
];

beforeEach(() => {
  rememberApplications(TEST_APPLICATIONS);
});

afterEach(() => {
  cleanup();
});
