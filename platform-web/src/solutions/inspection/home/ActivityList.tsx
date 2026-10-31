"use client";

import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { useApplication } from "../application";
import { activityHref, activityWords, momentLabel, type HomeActivity } from "./model";

/**
 * The latest things that happened (FA9, ADR-0203): published, revised, taken back, retired and sent, newest first, with
 * who and when. The server lists only what the person may see, so every row opens a page the person can open. A person
 * who has left is shown without a name rather than with a guess.
 */
export function ActivityList({ items }: { items: readonly HomeActivity[] }) {
  const application = useApplication();
  return (
    <ol className="chart-activity">
      {items.map((item) => (
        <li key={`${item.kind}-${item.id}-${item.at}`} className="chart-activity-item">
          <span className="chart-activity-mark" data-kind={item.kind} aria-hidden="true">
            <Icon name={item.about === "fill-in" ? "clipboard" : "audit"} className="chart-activity-icon" />
          </span>
          <span className="chart-activity-text">
            <Link href={activityHref(item, application)} className="chart-activity-name">
              {item.name || (item.about === "fill-in" ? "Fill-in" : "Template")}
            </Link>{" "}
            {activityWords(item.kind)}
            {item.who ? <span className="chart-activity-who"> by {item.who}</span> : null}
          </span>
          <time className="chart-activity-time" dateTime={item.at}>
            {momentLabel(item.at)}
          </time>
        </li>
      ))}
    </ol>
  );
}
