import type { Metadata } from "next";
import { LayoutBuilder } from "@/components/setup/LayoutBuilder";

export const metadata: Metadata = {
  title: "Page layout",
};

/** The builder of one page layout of an object; the address names the object and the layout ("new" starts one). */
export default function LayoutBuilderPage() {
  return <LayoutBuilder />;
}
