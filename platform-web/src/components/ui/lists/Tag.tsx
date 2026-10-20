import type { ReactNode } from "react";

/** The look of a tag: quiet by default, accent for "on", green for fine, red for trouble. */
export type TagTone = "quiet" | "on" | "ok" | "error";

const CLASSES: Record<TagTone, string> = {
  quiet: "badge",
  on: "badge badge-on",
  ok: "badge status-ok",
  error: "badge status-error",
};

/** A small status or type tag at the end of a list row. It says what the API answered; it decides nothing. */
export function Tag({ tone = "quiet", children }: { tone?: TagTone; children: ReactNode }) {
  return <span className={CLASSES[tone]}>{children}</span>;
}
