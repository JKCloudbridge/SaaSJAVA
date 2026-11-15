"use client";

import { optionsOfApplication } from "@/lib/applications/objectRoles";
import { useApplication } from "./application";
import { QualityHomePage } from "./home/QualityHome";
import { ManagementHomePage } from "./management/ManagementHome";

/**
 * The Home page of an application of the kind: the dashboard its solution file names, else the Quality Management System
 * one (MC3, ADR-0262). The choice is the application's own data, listed by the API with the application; no application
 * is named here, and a page that is not asked for by the file keeps the page it always had.
 */
export function HomePage() {
  const application = useApplication();
  return optionsOfApplication(application).dashboard === "management" ? <ManagementHomePage /> : <QualityHomePage />;
}
