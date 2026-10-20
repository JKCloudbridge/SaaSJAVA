import type { Metadata } from "next";
import { Suspense } from "react";
import { ChangeSetsPanel } from "@/components/setup/ChangeSetsPanel";

export const metadata: Metadata = {
  title: "Change sets",
};

/** Change sets: groups of changes to objects and fields that are published all together (a list; a row opens in the same pane). */
export default function ChangeSetsPage() {
  return (
    <Suspense fallback={null}>
      <ChangeSetsPanel />
    </Suspense>
  );
}
