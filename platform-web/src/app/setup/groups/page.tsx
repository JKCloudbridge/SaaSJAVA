import type { Metadata } from "next";
import { Suspense } from "react";
import { GroupsPanel } from "@/components/setup/GroupsPanel";

export const metadata: Metadata = {
  title: "Groups",
};

/** The public groups of the organization this address belongs to (a list; a row opens in the same pane). */
export default function GroupsPage() {
  return (
    <Suspense fallback={null}>
      <GroupsPanel />
    </Suspense>
  );
}
