"use client";

import { usePathname } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { RecordFormPage } from "@/components/records/RecordFormPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListRoute } from "@/components/ui/lists/useListRoute";
import { api } from "@/lib/api/client";
import { call } from "@/lib/records/call";
import { useObjectForm } from "@/lib/records/hooks";
import type { QueriedRecord } from "@/lib/records/types";
import { TemplateDetail } from "./TemplateDetail";
import { TEMPLATE_OBJECT, text } from "./template";

/**
 * The page of the "Templates" entry (Functional track FA3): a plain list of the templates the member may read, a New
 * button when the API says the member may create one, and one template opened in the same pane. The master list with its
 * filters and the lifecycle summary come with the approval sprint; this list has none of that on purpose.
 *
 * <p>It asks the API for everything and decides nothing: the entry that leads here was listed for a member who may read
 * templates, the list is the member's own query (record-level security included), and the New button follows the form
 * description the server gives.
 */
export function TemplatesPage() {
  return (
    <Suspense fallback={null}>
      <TemplatesScreen />
    </Suspense>
  );
}

type Loaded = { status: "loading" } | { status: "ready"; rows: QueriedRecord[] } | { status: "failed"; message: string };

function TemplatesScreen() {
  const route = useListRoute();
  const pathname = usePathname() ?? "";
  const { state: form } = useObjectForm(TEMPLATE_OBJECT);
  const [loaded, setLoaded] = useState<Loaded>({ status: "loading" });

  useEffect(() => {
    if (route.openId || route.creating) {
      return;
    }
    let live = true;
    void call(() =>
      api.POST("/api/v1/objects/{objectApiName}/records/query", {
        params: { path: { objectApiName: TEMPLATE_OBJECT } },
        body: { fields: ["name", "category", "subCategory", "number", "retired"], sort: [{ field: "name" }], limit: 200 },
      }),
    ).then((outcome) => {
      if (live) {
        setLoaded(
          outcome.ok
            ? { status: "ready", rows: outcome.data?.data ?? [] }
            : { status: "failed", message: outcome.failure.message },
        );
      }
    });
    return () => {
      live = false;
    };
  }, [route.openId, route.creating]);

  if (route.creating) {
    return (
      <RecordFormPage
        objectApiName={TEMPLATE_OBJECT}
        listHref={pathname}
        recordHref={(object, id) => `/records/${object}/${id}`}
        savedHref={(id) => `${pathname}?open=${id}`}
      />
    );
  }
  if (route.openId) {
    return <TemplateDetail id={route.openId} onBack={() => route.close()} />;
  }
  const canCreate = form.status === "ready" && form.form.actions.create;
  return (
    <ListPage<QueriedRecord>
      title="Templates"
      intro="A template is a sheet people fill in later. Open one to build it and keep its versions."
      items={loaded.status === "ready" ? loaded.rows : undefined}
      failure={loaded.status === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.record.id}
      primaryHeader="Name"
      primary={(row) => text(row.record.fields.name) || "Untitled template"}
      columns={[
        { key: "category", header: "Category", cell: (row) => text(row.record.fields.category), kind: "muted" },
        { key: "subCategory", header: "Sub-category", cell: (row) => text(row.record.fields.subCategory), kind: "muted" },
        { key: "number", header: "Number", cell: (row) => text(row.record.fields.number) || "Not numbered yet", kind: "muted" },
        {
          key: "retired",
          header: "Status",
          cell: (row) => (row.record.fields.retired === true ? <Tag>Retired</Tag> : null),
          kind: "tag",
        },
      ]}
      onOpen={(row) => route.open(row.record.id)}
      onNew={canCreate ? route.create : undefined}
      newLabel="New template"
      searchText={(row) => `${text(row.record.fields.name)} ${text(row.record.fields.category)} ${text(row.record.fields.number)}`}
      searchLabel="Search templates"
      emptyText="There are no templates yet."
      testId="templates-list"
    />
  );
}
