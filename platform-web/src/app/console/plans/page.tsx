import type { Metadata } from "next";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { PlansPanel } from "@/components/console/PlansPanel";

export const metadata: Metadata = {
  title: "Plans",
};

/** Plans, licence types and feature keys. */
export default function PlansPage() {
  return (
    <>
      <h1>Platform console</h1>
      <ConsoleFrame>
        <PlansPanel />
      </ConsoleFrame>
    </>
  );
}
