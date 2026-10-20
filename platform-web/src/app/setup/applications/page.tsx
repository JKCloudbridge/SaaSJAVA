import type { Metadata } from "next";
import { ApplicationsPanel } from "@/components/setup/ApplicationsPanel";

export const metadata: Metadata = {
  title: "Applications",
};

/** The applications of the organization: their navigation and the profiles that see them (a list; a row opens the builder). */
export default function ApplicationsPage() {
  return <ApplicationsPanel />;
}
