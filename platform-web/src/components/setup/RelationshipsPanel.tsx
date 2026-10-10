"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ON_DELETE_TEXT } from "./objectText";

type Relationships = components["schemas"]["ObjectRelationshipsView"];
type Relationship = components["schemas"]["RelationshipView"];

const TYPE_TEXT: Record<string, string> = {
  MANY_TO_ONE: "many to one",
  ONE_TO_ONE: "one to one",
  ONE_TO_MANY: "one to many",
  MANY_TO_MANY: "many to many",
};

function objectLink(apiName: string) {
  return <Link href={`/setup/objects/${encodeURIComponent(apiName)}`}>{apiName}</Link>;
}

function behaviour(relationship: Relationship): string {
  const parts = [`when the parent is removed: ${ON_DELETE_TEXT[relationship.onDelete] ?? relationship.onDelete}`];
  if (relationship.fieldType === "MASTER_DETAIL") {
    parts.push(relationship.reparentable ? "may move to another master" : "stays with its master");
  }
  if (relationship.required) {
    parts.push("required");
  }
  return parts.join("; ");
}

/**
 * The relationships of one object in both directions: the objects it points to (parents), the lists of other objects
 * that point to it (children) and the objects related through a junction object. A relationship is read from the lookup
 * and master-detail fields, so this only shows what the API answers; the behaviour is changed on the field itself.
 */
export function RelationshipsPanel({ objectApiName }: { objectApiName: string }) {
  const [relationships, setRelationships] = useState<Relationships | undefined>();
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let current = true;
    void (async () => {
      try {
        const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}/relationships", {
          params: { path: { objectApiName } },
        });
        if (current) {
          setRelationships(data?.data);
          setMissing(!data);
        }
      } catch {
        if (current) {
          setMissing(true);
        }
      }
    })();
    return () => {
      current = false;
    };
  }, [objectApiName]);

  if (missing || !relationships) {
    return null;
  }
  const { parents, children, manyToMany } = relationships;

  return (
    <section aria-labelledby="relationships-heading" data-testid="relationships">
      <h3 id="relationships-heading">Relationships</h3>
      {parents.length + children.length + manyToMany.length === 0 ? (
        <p className="hint">This object has no relationships yet. A lookup or master-detail field makes one.</p>
      ) : null}
      {parents.length > 0 ? (
        <>
          <h4>Points to</h4>
          <ul data-testid="relationship-parents">
            {parents.map((relationship) => (
              <li key={relationship.field}>
                {objectLink(relationship.parentObject)} through <code>{relationship.field}</code> (
                {TYPE_TEXT[relationship.type] ?? relationship.type}; {behaviour(relationship)})
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {children.length > 0 ? (
        <>
          <h4>Listed here</h4>
          <ul data-testid="relationship-children">
            {children.map((relationship) => (
              <li key={`${relationship.childObject}.${relationship.field}`}>
                <strong>{relationship.listLabel}</strong>: records of {objectLink(relationship.childObject)} that point to this
                one through <code>{relationship.field}</code> ({TYPE_TEXT[relationship.type] ?? relationship.type};{" "}
                {behaviour(relationship)})
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {manyToMany.length > 0 ? (
        <>
          <h4>Related through a junction object</h4>
          <ul data-testid="relationship-many">
            {manyToMany.map((relationship) => (
              <li key={`${relationship.viaObject}.${relationship.field}`}>
                {objectLink(relationship.otherObject)} through {relationship.viaObject ? objectLink(relationship.viaObject) : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}
