import type { Metadata } from "next";
import { Suspense } from "react";
import { ReleasesPanel } from "@/components/setup/ReleasesPanel";

export const metadata: Metadata = {
  title: "History of changes",
};

/** The history of published changes to objects and fields, and the rollback of the latest one (a list; a row opens in the same pane). */
export default function HistoryPage() {
  return (
    <Suspense fallback={null}>
      <ReleasesPanel />
    </Suspense>
  );
}
