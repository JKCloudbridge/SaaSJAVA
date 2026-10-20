import type { Metadata } from "next";
import { Suspense } from "react";
import { ObjectsPanel } from "@/components/setup/ObjectsPanel";

export const metadata: Metadata = {
  title: "Object settings",
};

/** The objects of the organization this address belongs to: the platform's standard ones and its own (a list). */
export default function ObjectsPage() {
  return (
    <Suspense fallback={null}>
      <ObjectsPanel />
    </Suspense>
  );
}
