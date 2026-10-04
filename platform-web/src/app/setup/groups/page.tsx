import type { Metadata } from "next";
import { GroupsPanel } from "@/components/setup/GroupsPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Groups",
};

/** The public groups of the organization this address belongs to. */
export default function GroupsPage() {
  return (
    <>
      <h1>Groups</h1>
      <SetupFrame>
        <GroupsPanel />
      </SetupFrame>
    </>
  );
}
