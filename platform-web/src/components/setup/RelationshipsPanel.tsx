"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { objectHref } from "./ObjectsPanel";
import { listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { ON_DELETE_TEXT } from "./objectText";

type Relationship = components["schemas"]["RelationshipView"];

const TYPE_TEXT: Record<string, string> = {
  MANY_TO_ONE: "Many to one",
  ONE_TO_ONE: "One to one",
  ONE_TO_MANY: "One to many",
  MANY_TO_MANY: "Many to many",
};

/** One line of the list, whichever direction the relationship runs in. */
interface Row {
  key: string;
  direction: "Points to" | "Listed here" | "Many to many";
  name: string;
  relatedObject: string;
  through: string;
  type: string;
  behaviour: string;
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
 * The Relationships tab (UA3, ADR-0170, ADR-0063): the objects this one points to, the lists of other objects that point
 * to it and the objects related through a junction object, as one list. A relationship is read from the lookup and
 * master-detail fields, so this only shows what the API answers; the behaviour is changed on the field itself.
 */
export function RelationshipsPanel({ object, refreshToken }: ObjectTabProps) {
  const objectApiName = object.apiName;
  const [rows, setRows] = useState<Row[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();

  useEffect(() => {
    let current = true;
    void (async () => {
      try {
        const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/relationships", {
          params: { path: { objectApiName } },
        });
        if (!current) {
          return;
        }
        if (!data) {
          setFailure(await failureText(error, response));
          return;
        }
        const { parents, children, manyToMany } = data.data;
        setFailure(undefined);
        setRows([
          ...parents.map(
            (relationship): Row => ({
              key: `parent:${relationship.field}`,
              direction: "Points to",
              name: relationship.fieldLabel || relationship.field,
              relatedObject: relationship.parentObject,
              through: relationship.field,
              type: TYPE_TEXT[relationship.type] ?? relationship.type,
              behaviour: behaviour(relationship),
            }),
          ),
          ...children.map(
            (relationship): Row => ({
              key: `child:${relationship.childObject}.${relationship.field}`,
              direction: "Listed here",
              name: relationship.listLabel,
              relatedObject: relationship.childObject,
              through: relationship.field,
              type: TYPE_TEXT[relationship.type] ?? relationship.type,
              behaviour: behaviour(relationship),
            }),
          ),
          ...manyToMany.map(
            (relationship): Row => ({
              key: `many:${relationship.viaObject}.${relationship.field}`,
              direction: "Many to many",
              name: relationship.otherObject,
              relatedObject: relationship.otherObject,
              through: relationship.viaObject ?? "",
              type: TYPE_TEXT.MANY_TO_MANY ?? "Many to many",
              behaviour: "",
            }),
          ),
        ]);
      } catch {
        if (current) {
          setFailure(COMMON_TEXT.network);
        }
      }
    })();
    return () => {
      current = false;
    };
    // A field added or removed on the object page can add or remove a relationship: ask again when it was read again.
  }, [objectApiName, refreshToken]);

  return (
    <ListPage<Row>
      title={tabTitle(object, "Relationships")}
      crumbs={listCrumbs(object, "Relationships")}
      items={rows}
      failure={failure}
      rowKey={(row) => row.key}
      primaryHeader="Name"
      primary={(row) => row.name}
      columns={[
        {
          key: "related",
          header: "Related object",
          cell: (row) => <Link href={objectHref(row.relatedObject)}>{row.relatedObject}</Link>,
        },
        {
          key: "through",
          header: "Through",
          kind: "muted",
          cell: (row) => (row.through ? <code>{row.through}</code> : "—"),
        },
        { key: "type", header: "Kind", kind: "muted", cell: (row) => row.type },
        { key: "behaviour", header: "Behaviour", kind: "muted", cell: (row) => row.behaviour || "—" },
        { key: "direction", header: "Direction", kind: "tag", cell: (row) => <Tag>{row.direction}</Tag> },
      ]}
      searchText={(row) => `${row.name} ${row.relatedObject} ${row.through} ${row.direction}`}
      searchLabel="Search relationships"
      emptyText="This object has no relationships yet. A lookup or master-detail field makes one."
      testId="relationships"
      rowTestId="relationship-row"
    />
  );
}
