import type { Metadata } from "next";
import { ReleasesPanel } from "@/components/setup/ReleasesPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "History of changes",
};

/** The history of published changes to objects and fields, and the rollback of the latest one. */
export default function HistoryPage() {
  return (
    <>
      <h1>History of changes</h1>
      <SetupFrame>
        <ReleasesPanel />
      </SetupFrame>
    </>
  );
}