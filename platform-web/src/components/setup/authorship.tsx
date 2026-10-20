"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";

type AuthorshipView = components["schemas"]["AuthorshipView"];

/** The kinds of definition the authorship answer lists (UA3, ADR-0169). */
export type AuthorshipKind = "OBJECT" | "FIELD" | "RECORD_TYPE" | "LAYOUT" | "RECORD_PAGE" | "VALIDATION_RULE";

/** Who made and who last changed what, for one object. Without an answer every lookup is empty and the screens draw a dash. */
export interface Authorship {
  /** The entry of a definition, or undefined when the platform defines it or the answer is not here (yet). */
  of: (kind: AuthorshipKind, apiName: string) => AuthorshipView | undefined;
}

const NOBODY: Authorship = { of: () => undefined };

/**
 * Asks the API who made and who last changed the definitions of an object. It is a convenience of the screens: a refusal,
 * a slow answer or a person who may not look all leave the dashes in place, and nothing else on the page waits for it.
 * It asks again whenever `refreshToken` changes, that is, after the object itself was read again.
 */
export function useAuthorship(objectApiName: string, refreshToken: number): Authorship {
  const [loaded, setLoaded] = useState<{ object: string; token: number; entries: AuthorshipView[] } | undefined>();

  useEffect(() => {
    let current = true;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}/authorship", {
          params: { path: { objectApiName } },
        });
        if (current && data) {
          setLoaded({ object: objectApiName, token: refreshToken, entries: data.data });
        }
      } catch {
        // The dashes stay: who made a definition is never worth failing a screen for.
      }
    })();
    return () => {
      current = false;
    };
  }, [objectApiName, refreshToken]);

  if (!loaded || loaded.object !== objectApiName) {
    return NOBODY;
  }
  const byKey = new Map(loaded.entries.map((entry) => [`${entry.kind}:${entry.apiName}`, entry]));
  return { of: (kind, apiName) => byKey.get(`${kind}:${apiName}`) };
}

/**
 * Who, and when: the name with the date quietly after it (the mockup's “Name — date”). `platformDefined` says the
 * definition is the platform's own, which has no entry because it is a file and not a row; it reads “Platform”. Where the
 * name cannot be shown (a person who left, an answer that has not come) the dash stands.
 */
export function Author({
  authorship,
  kind,
  apiName,
  which,
  platformDefined = false,
}: {
  authorship: Authorship;
  kind: AuthorshipKind;
  apiName: string;
  which: "created" | "updated";
  platformDefined?: boolean;
}) {
  const entry = authorship.of(kind, apiName);
  if (!entry) {
    return <>{platformDefined ? "Platform" : "—"}</>;
  }
  const name = which === "created" ? entry.createdBy : entry.updatedBy;
  const at = which === "created" ? entry.createdAt : entry.updatedAt;
  return (
    <>
      {name ?? "—"}{" "}
      <span className="dl-value muted">
        — <time dateTime={at}>{new Date(at).toLocaleDateString()}</time>
      </span>
    </>
  );
}
