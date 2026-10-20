import type { Metadata } from "next";
import { Suspense } from "react";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { PlansPanel } from "@/components/console/PlansPanel";

export const metadata: Metadata = {
  title: "Plans",
};

/** Plans, licence types and feature keys (a list; a plan opens in the same pane). */
export default function PlansPage() {
  return (
    <ConsoleFrame>
      <Suspense fallback={null}>
        <PlansPanel />
      </Suspense>
    </ConsoleFrame>
  );
}
