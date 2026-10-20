import type { Metadata } from "next";
import { Suspense } from "react";
import { ConsoleFrame } from "@/components/console/ConsoleFrame";
import { PeoplePanel } from "@/components/console/PeoplePanel";

export const metadata: Metadata = {
  title: "Platform people",
};

/** Who holds a platform role (a list; a person opens in the same pane). */
export default function PeoplePage() {
  return (
    <ConsoleFrame>
      <Suspense fallback={null}>
        <PeoplePanel />
      </Suspense>
    </ConsoleFrame>
  );
}
