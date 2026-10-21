"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailPage, DetailSection } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { OBJECT_SETTINGS_HREF } from "@/lib/shell/areas";
import { KIND_TEXT, problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ObjectSummary = components["schemas"]["ObjectSummaryView"];

const EMPTY = { name: "", label: "", pluralLabel: "", description: "" };

/** The address of one object's settings: the list's rows and the "created" notice both lead there. */
export const objectHref = (apiName: string) => `${OBJECT_SETTINGS_HREF}/${encodeURIComponent(apiName)}`;

/**
 * Object settings (UA3, ADR-0170): the standard objects of the platform and the organization's own, as a list with a
 * search box; a row opens the object, New opens the form that makes one. The page shows what the API answers and its own
 * words for every refusal; it never decides who may do what, which names are allowed or which objects exist.
 */
export function ObjectsPanel() {
  const [objects, setObjects] = useState<ObjectSummary[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState(EMPTY);
  const { id: workingSet } = useWorkingSet();
  const offersManage = useOffers("metadata.manage");

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/objects");
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setObjects(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;
  const messages = <ActionMessages notice={notice} problem={problem} testId="objects" />;

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { error, response } = workingSet
        ? await addToSet(workingSet, {
            kind: "CREATE_OBJECT",
            objectApiName: `${draft.name.trim()}__c`,
            createObject: draft,
          })
        : await api.POST("/api/v1/metadata/objects", { body: draft });
      if (response.ok) {
        setDraft(EMPTY);
        screen.back(true);
        return { error, response, text: workingSet ? DRAFTED_TEXT : "The object was created." };
      }
      return { error, response, problem: problemText(error, response) };
    });
  }

  if (screen.creating) {
    return (
      <DetailPage
        listLabel="Object settings"
        onBack={() => screen.back()}
        title="New object"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={() => screen.back()}>
              Cancel
            </button>
            <button type="submit" form="object-form" className="button" disabled={busy}>
              Create the object
            </button>
          </>
        }
        testId="object-new"
      >
        <form id="object-form" onSubmit={create} noValidate>
          <DetailSection title="Detail">
            <DetailGrid>
              <div className="field">
                <label htmlFor="object-name">Name</label>
                <input
                  id="object-name"
                  value={draft.name}
                  maxLength={40}
                  onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                  required
                  aria-describedby="object-name-hint"
                />
                <p className="hint" id="object-name-hint">
                  The permanent API name used by code and integrations, for example Employee. The platform adds the ending __c.
                </p>
              </div>
              <div className="field">
                <label htmlFor="object-label">Label</label>
                <input
                  id="object-label"
                  value={draft.label}
                  maxLength={80}
                  onChange={(event) => setDraft({ ...draft, label: event.target.value })}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="object-plural">Plural label</label>
                <input
                  id="object-plural"
                  value={draft.pluralLabel}
                  maxLength={80}
                  onChange={(event) => setDraft({ ...draft, pluralLabel: event.target.value })}
                  required
                />
              </div>
              <div className="field span-2">
                <label htmlFor="object-description">Description</label>
                <input
                  id="object-description"
                  value={draft.description}
                  maxLength={500}
                  onChange={(event) => setDraft({ ...draft, description: event.target.value })}
                />
              </div>
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  return (
    <ListPage<ObjectSummary>
      title="Object settings"
      items={objects}
      failure={failure}
      rowKey={(row) => row.apiName}
      primaryHeader="Label"
      primary={(row) => row.label}
      columns={[
        {
          key: "api",
          header: "API name",
          kind: "muted",
          cell: (row) => <code>{row.apiName}</code>,
        },
        {
          key: "type",
          header: "Type",
          kind: "tag",
          cell: (row) => <Tag tone={row.kind === "CUSTOM" ? "on" : "quiet"}>{KIND_TEXT[row.kind] ?? row.kind}</Tag>,
        },
      ]}
      openHref={(row) => objectHref(row.apiName)}
      searchText={(row) => `${row.label} ${row.apiName} ${KIND_TEXT[row.kind] ?? row.kind}`}
      searchLabel="Search objects"
      onNew={offersManage ? screen.create : undefined}
      notices={messages}
      emptyText="There are no objects yet."
      testId="objects"
      rowTestId="object-row"
    />
  );
}
