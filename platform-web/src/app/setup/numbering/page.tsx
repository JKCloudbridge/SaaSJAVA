import type { Metadata } from "next";
import { Suspense } from "react";
import { NumberingTabs } from "@/components/setup/NumberingTabs";

export const metadata: Metadata = { title: "Numbering" };

/** The organization's number formats and next counts (FA6B) and the rules that route a document by its number (MC1.1). */
export default function NumberingPage() {
  return (
    <Suspense fallback={null}>
      <NumberingTabs />
    </Suspense>
  );
}
