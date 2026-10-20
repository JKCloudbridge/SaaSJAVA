"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { DetailPage } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { objectSectionHref } from "@/lib/shell/areas";
import { Author } from "./authorship";
import { LayoutAssignmentsPanel } from "./LayoutAssignmentsPanel";
import { LayoutPreview } from "./LayoutPreview";
import { detailParents, listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectView = components["schemas"]["ObjectView"];
type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];

/** The fields a layout can show: the object's own, standard and custom ones; the system fields are the platform's. */
function showable(object: ObjectView): FieldView[] {
  return object.fields.filter((field) => field.kind !== "SYSTEM" && !field.retired);
}

/**
 * The Page layouts tab of an object (UA3, ADR-0170, ADR-0069 to ADR-0071): the layouts as a list (name, API name, sections,
 * who made and last changed it); a row opens the builder, where a layout is made and changed (drag fields into sections),
 * and New starts a new one at the builder's address. “Layout assignments” opens which layout each profile and record type
 * gets. Removing is done here, live (one step in the history) or, when a change set is chosen, as a draft. The page shows
 * what the API answers: what depends on a layout is the API's to decide.
 */
export function LayoutsPanel({ object, reload: reloadObject, refreshToken, authorship }: ObjectTabProps) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");
  const showAssignments = useSearchParams()?.get("view") === "assignments";
  const [layouts, setLayouts] = useState<LayoutView[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [previewing, setPreviewing] = useState<string | undefined>();

  const load = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", {
        params: { path: { objectApiName } },
      });
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setLayouts(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(load);
    }
  }, [load, managed]);

  const done = useCallback(async () => {
    await load();
    await reloadObject();
  }, [load, reloadObject]);
  const { busy, notice, problem, act, clear } = useAction(done);

  const base = `/setup/objects/${encodeURIComponent(objectApiName)}/layouts`;
  const section = objectSectionHref(objectApiName, "layouts");
  const messages = <ActionMessages notice={notice} problem={problem} testId="layouts" />;

  function remove(layout: LayoutView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_LAYOUT", objectApiName, itemApiName: layout.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/layouts/{layoutApiName}", {
            params: { path: { objectApiName, layoutApiName: layout.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The page layout was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  if (showAssignments && !managed) {
    return (
      <DetailPage
        listLabel="Page layouts"
        parents={detailParents(object)}
        backHref={section}
        title="Layout assignments"
        testId="layout-assignments-page"
      >
        <LayoutAssignmentsPanel object={object} refreshToken={refreshToken} />
      </DetailPage>
    );
  }

  if (managed) {
    return (
      <ListPage<LayoutView>
        title={tabTitle(object, "Page layouts")}
        crumbs={listCrumbs(object, "Page layouts")}
        items={[]}
        rowKey={(row) => row.apiName}
        primaryHeader="Name"
        primary={(row) => row.label}
        columns={[]}
        emptyText="The records of this object belong to the platform, so it has no page layouts."
        testId="layouts"
      />
    );
  }

  const fields = showable(object);

  return (
    <ListPage<LayoutView>
      title={tabTitle(object, "Page layouts")}
      crumbs={listCrumbs(object, "Page layouts")}
      intro="A page layout is the structure of a record form: sections with fields in them. It must show every required field of the object."
      items={layouts}
      failure={failure}
      rowKey={(row) => row.apiName}
      primaryHeader="Name"
      primary={(row) => row.label}
      primaryExtra={(row) => (row.description ? <span className="hint"> {row.description}</span> : null)}
      columns={[
        { key: "api", header: "API name", kind: "muted", cell: (row) => <code>{row.apiName}</code> },
        { key: "sections", header: "Sections", kind: "muted", cell: (row) => row.definition.sections.length },
        {
          key: "created",
          header: "Created by",
          kind: "muted",
          cell: (row) => <Author authorship={authorship} kind="LAYOUT" apiName={row.apiName} which="created" />,
        },
        {
          key: "modified",
          header: "Modified by",
          kind: "muted",
          cell: (row) => <Author authorship={authorship} kind="LAYOUT" apiName={row.apiName} which="updated" />,
        },
        {
          key: "actions",
          header: "Actions",
          kind: "actions",
          cell: (row) => (
            <>
              <button
                type="button"
                className="link-button"
                onClick={() => setPreviewing(previewing === row.apiName ? undefined : row.apiName)}
                aria-expanded={previewing === row.apiName}
              >
                Preview
              </button>{" "}
              {offersManage ? (
                <button type="button" className="link-button" disabled={busy} onClick={() => remove(row)}>
                  Remove
                </button>
              ) : null}
            </>
          ),
        },
      ]}
      openHref={(row) => `${base}/${encodeURIComponent(row.apiName)}`}
      searchText={(row) => `${row.label} ${row.apiName}`}
      searchLabel="Search layouts"
      newHref={offersManage ? `${base}/new` : undefined}
      tools={
        <Link className="button button-secondary" href={`${section}&view=assignments`} onClick={clear}>
          Layout assignments
        </Link>
      }
      notices={messages}
      below={(layouts ?? [])
        .filter((layout) => layout.apiName === previewing)
        .map((layout) => (
          <LayoutPreview key={layout.apiName} layout={layout.definition} fields={fields} title={layout.label} />
        ))}
      emptyText="No page layouts yet."
      testId="layouts"
      rowTestId="layout-row"
    />
  );
}
