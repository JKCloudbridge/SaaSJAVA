import type { Metadata } from "next";
import { ApplicationHome } from "@/components/apps/ApplicationHome";

export const metadata: Metadata = {
  title: "Application",
};

/** An application of the organization. It opens on its first page; which pages it has is the API's answer. */
export default function ApplicationPage() {
  return <ApplicationHome />;
}
