import type { Metadata } from "next";
import { ObjectDetailPanel } from "@/components/setup/ObjectDetailPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Object",
};

/** One object of the organization with its fields. The object is the address of this page. */
export default function ObjectPage() {
  return (
    <>
      <h1>Objects</h1>
      <SetupFrame>
        <ObjectDetailPanel />
      </SetupFrame>
    </>
  );
}
