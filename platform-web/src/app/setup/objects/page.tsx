import type { Metadata } from "next";
import { ObjectsPanel } from "@/components/setup/ObjectsPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Objects",
};

/** The objects of the organization this address belongs to: the platform's standard ones and its own. */
export default function ObjectsPage() {
  return (
    <>
      <h1>Objects</h1>
      <SetupFrame>
        <ObjectsPanel />
      </SetupFrame>
    </>
  );
}
