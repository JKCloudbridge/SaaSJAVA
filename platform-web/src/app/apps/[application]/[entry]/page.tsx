import type { Metadata } from "next";
import { ApplicationEntryView } from "@/components/apps/ApplicationEntryView";

export const metadata: Metadata = {
  title: "Application page",
};

/** One page of an application: a custom page, or (until the record screens exist) a note that it is being prepared. */
export default function ApplicationEntryPage() {
  return <ApplicationEntryView />;
}
