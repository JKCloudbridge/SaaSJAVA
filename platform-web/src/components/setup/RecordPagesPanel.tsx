"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { DetailPage } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { allComponents, componentDraftOf } from "@/lib/builder/recordPageDraft";
import { useOffers } from "@/lib/session/SessionProvider";
import { objectSectionHref } from "@/lib/shell/areas";
import { detailParents, listCrumbs, tabTitle, type ObjectTabProps } from "./objectTab";
import { problemText } from "./objectText";
import { RecordPageAssignmentsPanel } from "./RecordPageAssignmentsPanel";
import { RecordPagePreview } from "./RecordPagePreview";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type FieldView = components["schemas"]["FieldView"];
type LayoutView = components["schemas"]["LayoutView"];
type RecordPageView = components["schemas"]["RecordPageView"];
type RecordPageAssignmentView = components["schemas"]["RecordPageAssignmentView"];

/** Who a record page is chosen for, in words: everyone, a profile, a record type or a profile with a record type. */
function assignedTo(assignment: RecordPageAssignmentView): string {
  const profile =
    assignment.profileId == null ? undefined : (assignment.profileName ?? "A profile that was deleted (ignored)");
  if (profile && assignment.recordType) {
    return `${profile}, ${assignment.recordType}`;
  }
  if (profile) {
    return profile;
  }
  return assignment.recordType ? `Record type ${assignment.recordType}` : "Everyone (the default)";
}

/**
 * The Record pages tab of an object (UA3, ADR-0170, ADR-0072): the pages as a list (record page, who it is assigned to,
 * status); a row opens the builder, where a page is made and changed (its tabs are arranged by drag and drop), and New
 * starts a new one at the builder's address. “Record page assignments” opens which page each profile and record type
 * gets. Removing is done here, live (one step in the history) or, when a change set is chosen, as a draft. The page shows
 * what the API answers: that the layout and the fields exist, and that a related list follows a relationship, is the
 * API's to decide.
 */
export function RecordPagesPanel({ object, reload: reloadObject, refreshToken }: ObjectTabProps) {
  const objectApiName = object.apiName;
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");
  const showAssignments = useSearchParams()?.get("view") === "assignments";
  const [pages, setPages] = useState<RecordPageView[] | undefined>();
  const [assignments, setAssignments] = useState<RecordPageAssignmentView[]>([]);
  const [layouts, setLayouts] = useState<LayoutView[]>([]);
  const [others, setOthers] = useState<Record<string, FieldView[]>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [previewing, setPreviewing] = useState<string | undefined>();

  const ensure = useCallback(async (name: string) => {
    if (!name) {
      return;
    }
    try {
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName: name } } });
      if (data) {
        setOthers((known) => ({ ...known, [name]: data.data.fields }));
      }
    } catch {
      // The related list then shows no columns; opening the preview again tries once more.
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const path = { objectApiName };
      const [list, layoutList, assigned] = await Promise.all([
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-pages", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/layouts", { params: { path } }),
        api.GET("/api/v1/metadata/objects/{objectApiName}/record-page-assignments", { params: { path } }),
      ]);
      if (!list.data || !layoutList.data) {
        const first = list.data ? layoutList : list;
        setFailure(await failureText(first.error, first.response));
        return;
      }
      setFailure(undefined);
      setPages(list.data.data);
      setLayouts(layoutList.data.data);
      // Who a page is chosen for is a convenience of the list: without it the column says nothing, the list still works.
      setAssignments(assigned.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  const managed = object.managedBy != null;
  useEffect(() => {
    if (!managed) {
      void Promise.resolve().then(load);
    }
  }, [load, managed, refreshToken]);

  const done = useCallback(async () => {
    await load();
    await reloadObject();
  }, [load, reloadObject]);
  const { busy, notice, problem, act, clear } = useAction(done);

  const base = `/setup/objects/${encodeURIComponent(objectApiName)}/record-pages`;
  const section = objectSectionHref(objectApiName, "record-pages");
  const fieldsOf = (name: string) => (name === objectApiName ? object.fields : (others[name] ?? []));
  const messages = <ActionMessages notice={notice} problem={problem} testId="record-pages" />;

  function remove(page: RecordPageView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_RECORD_PAGE", objectApiName, itemApiName: page.apiName })
        : await api.DELETE("/api/v1/metadata/objects/{objectApiName}/record-pages/{pageApiName}", {
            params: { path: { objectApiName, pageApiName: page.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The record page was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  if (showAssignments && !managed) {
    return (
      <DetailPage
        listLabel="Record pages"
        parents={detailParents(object)}
        backHref={section}
        title="Record page assignments"
        testId="record-page-assignments-page"
      >
        <RecordPageAssignmentsPanel object={object} refreshToken={refreshToken} />
      </DetailPage>
    );
  }

  if (managed) {
    return (
      <ListPage<RecordPageView>
        title={tabTitle(object, "Record pages")}
        crumbs={listCrumbs(object, "Record pages")}
        items={[]}
        rowKey={(row) => row.apiName}
        primaryHeader="Record page"
        primary={(row) => row.label}
        columns={[]}
        emptyText="The records of this object belong to the platform, so it has no record pages."
        testId="record-pages"
      />
    );
  }

  const assignedOf = (page: RecordPageView) => assignments.filter((one) => one.recordPage === page.apiName);

  return (
    <ListPage<RecordPageView>
      title={tabTitle(object, "Record pages")}
      crumbs={listCrumbs(object, "Record pages")}
      intro="A record page puts the details of a layout together with a header, related lists, tabs and actions. When a part is shown can depend on a field or on an ability of the person."
      items={pages}
      failure={failure}
      rowKey={(row) => row.apiName}
      primaryHeader="Record page"
      primary={(row) => row.label}
      primaryExtra={(row) => (row.description ? <span className="hint"> {row.description}</span> : null)}
      columns={[
        {
          key: "assigned",
          header: "Assigned to",
          kind: "muted",
          cell: (row) => {
            const list = assignedOf(row);
            return list.length === 0 ? "—" : list.map(assignedTo).join("; ");
          },
        },
        { key: "layout", header: "Layout", kind: "muted", cell: (row) => <code>{row.definition.layout}</code> },
        {
          key: "actions",
          header: "Actions",
          kind: "actions",
          cell: (row) => (
            <>
              <button
                type="button"
                className="link-button"
                aria-expanded={previewing === row.apiName}
                onClick={() => {
                  const open = previewing === row.apiName;
                  setPreviewing(open ? undefined : row.apiName);
                  if (!open) {
                    allComponents(row.definition.components.map((component, at) => componentDraftOf(component, at)))
                      .filter((component) => component.object)
                      .forEach((component) => void ensure(component.object));
                  }
                }}
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
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => (
            <Tag tone={assignedOf(row).length > 0 ? "ok" : "quiet"}>
              {assignedOf(row).length > 0 ? "In use" : "Not assigned"}
            </Tag>
          ),
        },
      ]}
      openHref={(row) => `${base}/${encodeURIComponent(row.apiName)}`}
      searchText={(row) => `${row.label} ${row.apiName} ${row.definition.layout}`}
      searchLabel="Search record pages"
      newHref={offersManage ? `${base}/new` : undefined}
      tools={
        <Link className="button button-secondary" href={`${section}&view=assignments`} onClick={clear}>
          Record page assignments
        </Link>
      }
      notices={messages}
      below={(pages ?? [])
        .filter((page) => page.apiName === previewing)
        .map((page) => (
          <RecordPagePreview
            key={page.apiName}
            page={page.definition}
            title={page.label}
            layouts={layouts}
            fields={object.fields}
            fieldsOf={fieldsOf}
          />
        ))}
      emptyText="No record pages yet."
      testId="record-pages"
      rowTestId="record-page-row"
    />
  );
}
