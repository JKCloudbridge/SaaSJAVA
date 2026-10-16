import type { Metadata } from "next";
import { ObjectListRoute } from "@/components/records/RecordRoutes";

export const metadata: Metadata = {
  title: "Records",
};

/** The list of the records of an object (Sprint 19): drawn entirely from what the API says the member may see and do. */
export default function ObjectListPageRoute() {
  return <ObjectListRoute />;
}
