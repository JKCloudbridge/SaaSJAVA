import type { Metadata } from "next";
import { ApplicationsPanel } from "@/components/setup/ApplicationsPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Applications",
};

/** The applications of the organization: their navigation and the profiles that see them. */
export default function ApplicationsPage() {
  return (
    <>
      <h1>Applications</h1>
      <SetupFrame>
        <ApplicationsPanel />
      </SetupFrame>
    </>
  );
}
