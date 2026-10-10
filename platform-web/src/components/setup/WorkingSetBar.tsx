"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useWorkingSet } from "./workingSet";

type ChangeSetView = components["schemas"]["ChangeSetView"];

/**
 * Where the changes of the object pages go: live at once, or into one of the open change sets. Shown above the object
 * pages only. It lists what the API answers and says nothing when the list cannot be read (for example for a person who
 * may only look).
 */
export function WorkingSetBar() {
  const { id, select } = useWorkingSet();
  const [open, setOpen] = useState<ChangeSetView[]>([]);

  useEffect(() => {
    let current = true;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/metadata/change-sets");
        if (current && data) {
          const drafts = data.data.filter((set) => set.status === "DRAFT");
          setOpen(drafts);
          if (id && !drafts.some((set) => set.id === id)) {
            // The chosen set was published or discarded meanwhile: changes go live again.
            select(undefined);
          }
        }
      } catch {
        // No list: the bar stays quiet and changes go live.
      }
    })();
    return () => {
      current = false;
    };
  }, [id, select]);

  if (open.length === 0) {
    return null;
  }
  const chosen = open.find((set) => set.id === id);

  return (
    <section aria-label="Where changes go" className="stack" data-testid="working-set">
      <label htmlFor="working-set">Changes on the object pages go</label>
      <select id="working-set" value={chosen?.id ?? ""} onChange={(event) => select(event.target.value || undefined)}>
        <option value="">live at once</option>
        {open.map((set) => (
          <option key={set.id} value={set.id}>
            into the change set “{set.name}”
          </option>
        ))}
      </select>
      <p className="hint">
        {chosen
          ? "Nothing you change is live until the change set is published."
          : "Each change goes live at once and is one step in the history."}
      </p>
    </section>
  );
}
