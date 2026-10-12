import type { Metadata } from "next";
import { ChangeSetsPanel } from "@/components/setup/ChangeSetsPanel";
import { SetupFrame } from "@/components/setup/SetupFrame";

export const metadata: Metadata = {
  title: "Change sets",
};

/** Change sets: groups of changes to objects and fields that are published all together. */
export default function ChangeSetsPage() {
  return (
    <>
      <h1>Change sets</h1>
      <SetupFrame>
        <ChangeSetsPanel />
      </SetupFrame>
    </>
  );
}