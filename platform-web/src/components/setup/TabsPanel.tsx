"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, type ActionResult } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { uploadScript } from "@/lib/builder/uploadScript";
import { useOffers } from "@/lib/session/SessionProvider";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type TabView = components["schemas"]["TabView"];
type CustomPageView = components["schemas"]["CustomPageView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type CustomPageDefinition = components["schemas"]["CustomPageDefinition"];

const NEW_PAGE = "";
const SUFFIX = "__c";

interface Row {
  key: string;
  label: string;
  kind: "Object" | "Custom";
  source: string;
  tab?: TabView;
}

interface Draft {
  label: string;
  name: string;
  description: string;
  page: string;
  file: File | undefined;
  backend: string;
}

const EMPTY: Draft = { label: "", name: "", description: "", page: NEW_PAGE, file: undefined, backend: "" };

function readableSize(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;
}

function pageApiNameOf(name: string): string {
  return name.endsWith(SUFFIX) ? name : name + SUFFIX;
}

/**
 * The tabs of the organization, one list with a type tag. Every object has a tab already (it opens the object's list), so
 * those rows are listed and need nothing made; a custom tab shows a custom page, a script the organization uploads. A custom
 * tab opens as a page of its own (change it, remove it); New makes a tab, with a new custom page from a script file if wanted.
 * Below the list are the custom pages themselves (replace a script, remove a page). The script is stored and shown in a
 * sandbox in the member's browser; the platform never runs it on its servers. The page shows what the API answers: that the
 * script is acceptable, and what depends on a tab or a page, is the API's to decide.
 */
export function TabsPanel() {
  const { id: workingSet } = useWorkingSet();
  const [tabs, setTabs] = useState<TabView[] | undefined>();
  const [pages, setPages] = useState<CustomPageView[]>([]);
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState(false);
  const [replacing, setReplacing] = useState<string | undefined>();
  const offersManage = useOffers("metadata.manage");

  const reload = useCallback(async () => {
    try {
      const [tabList, pageList, objectList] = await Promise.all([
        api.GET("/api/v1/metadata/tabs"),
        api.GET("/api/v1/metadata/custom-pages"),
        api.GET("/api/v1/metadata/objects"),
      ]);
      if (!tabList.data) {
        setFailure(await failureText(tabList.error, tabList.response));
        return;
      }
      setFailure(undefined);
      setTabs(tabList.data.data);
      setPages(pageList.data?.data ?? []);
      setObjects(objectList.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  const pageLabels = useMemo(() => new Map(pages.map((one) => [one.apiName, one.label])), [pages]);

  const rows: Row[] | undefined = tabs && [
    ...objects.map(
      (one): Row => ({
        key: `object:${one.apiName}`,
        label: one.label,
        kind: "Object",
        source: `${one.label} object: its list`,
      }),
    ),
    ...tabs.map(
      (tab): Row => ({
        key: `tab:${tab.apiName}`,
        label: tab.label,
        kind: "Custom",
        source: tab.definition.customPage
          ? (pageLabels.get(tab.definition.customPage) ?? `${tab.definition.customPage} (not found)`)
          : "No custom page",
        tab,
      }),
    ),
  ];

  /** One change, live or into the chosen change set; the first one that fails is the answer. */
  async function send(
    live: () => Promise<{ error?: unknown; response: Response }>,
    drafted: () => Promise<{ error?: unknown; response: Response }>,
  ) {
    return workingSet ? drafted() : live();
  }

  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async (): Promise<ActionResult> => {
      let customPage = draft.page;
      if (customPage === NEW_PAGE) {
        if (!draft.file) {
          return { response: new Response(null, { status: 400 }), problem: "Choose the script file of the custom page." };
        }
        const stored = await uploadScript(draft.file);
        if (!stored.ok) {
          return { response: new Response(null, { status: 400 }), problem: stored.message };
        }
        const content: CustomPageDefinition = {
          frontend: { assetId: stored.asset.id, sha256: stored.asset.sha256, size: stored.asset.size },
          ...(draft.backend.trim() ? { backend: { reference: draft.backend.trim() } } : {}),
        };
        const page = { name: draft.name, label: draft.label, description: draft.description, definition: content };
        const made = await send(
          () => api.POST("/api/v1/metadata/custom-pages", { body: page }),
          () => addToSet(workingSet ?? "", { kind: "CREATE_CUSTOM_PAGE", createCustomPage: page }),
        );
        if (!made.response.ok) {
          return { error: made.error, response: made.response, problem: problemText(made.error, made.response) };
        }
        customPage = pageApiNameOf(draft.name);
      }
      const request = {
        name: draft.name,
        label: draft.label,
        description: draft.description,
        definition: { kind: "CUSTOM" as const, customPage },
      };
      const result = await send(
        () => api.POST("/api/v1/metadata/tabs", { body: request }),
        () => addToSet(workingSet ?? "", { kind: "CREATE_TAB", createTab: request }),
      );
      if (result.response.ok) {
        setDraft(EMPTY);
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The tab was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const update = (tab: TabView) => (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void act(async (): Promise<ActionResult> => {
      const definition = { kind: "CUSTOM" as const, customPage: draft.page || undefined };
      const body = { label: draft.label, description: draft.description, definition, version: tab.version };
      const result = await send(
        () => api.PUT("/api/v1/metadata/tabs/{apiName}", { params: { path: { apiName: tab.apiName } }, body }),
        () => addToSet(workingSet ?? "", { kind: "UPDATE_TAB", itemApiName: tab.apiName, updateTab: body }),
      );
      if (result.response.ok) {
        setEditing(false);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The tab was saved.",
        problem: problemText(result.error, result.response),
      };
    });
  };

  function removeTab(tab: TabView) {
    void act(async () => {
      const result = await send(
        () => api.DELETE("/api/v1/metadata/tabs/{apiName}", { params: { path: { apiName: tab.apiName } } }),
        () => addToSet(workingSet ?? "", { kind: "DELETE_TAB", itemApiName: tab.apiName }),
      );
      if (result.response.ok) {
        screen.back(true);
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The tab was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function removePage(page: CustomPageView) {
    void act(async () => {
      const result = await send(
        () => api.DELETE("/api/v1/metadata/custom-pages/{apiName}", { params: { path: { apiName: page.apiName } } }),
        () => addToSet(workingSet ?? "", { kind: "DELETE_CUSTOM_PAGE", itemApiName: page.apiName }),
      );
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The custom page was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function replaceScript(page: CustomPageView, file: File) {
    setReplacing(undefined);
    void act(async (): Promise<ActionResult> => {
      const stored = await uploadScript(file);
      if (!stored.ok) {
        return { response: new Response(null, { status: 400 }), problem: stored.message };
      }
      const body = {
        label: page.label,
        description: page.description,
        definition: {
          ...page.definition,
          frontend: { assetId: stored.asset.id, sha256: stored.asset.sha256, size: stored.asset.size },
        },
        version: page.version,
      };
      const result = await send(
        () =>
          api.PUT("/api/v1/metadata/custom-pages/{apiName}", {
            params: { path: { apiName: page.apiName } },
            body,
          }),
        () => addToSet(workingSet ?? "", { kind: "UPDATE_CUSTOM_PAGE", itemApiName: page.apiName, updateCustomPage: body }),
      );
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The script of the custom page was replaced.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const messages = <ActionMessages notice={notice} problem={problem} testId="tabs" />;
  const usedBy = (page: CustomPageView) => (tabs ?? []).filter((tab) => tab.definition.customPage === page.apiName).length;

  const pageChoices = pages.map((one) => (
    <option key={one.apiName} value={one.apiName}>
      {one.label} ({one.apiName})
    </option>
  ));

  const fields = (existing: TabView | undefined) => (
    <>
      {existing ? (
        <p className="span-2">
          <code>{existing.apiName}</code>. The name of a tab never changes.
        </p>
      ) : (
        <div className="field span-2">
          <label htmlFor="tab-name">Name</label>
          <input
            id="tab-name"
            value={draft.name}
            maxLength={40}
            onChange={(event) => setDraft({ ...draft, name: event.target.value })}
            aria-describedby="tab-name-hint"
          />
          <p className="hint" id="tab-name-hint">
            The permanent name, starting with a capital letter, for example FieldMap. The platform adds the ending __c.
          </p>
        </div>
      )}
      <div className="field">
        <label htmlFor="tab-label">Label</label>
        <input
          id="tab-label"
          value={draft.label}
          maxLength={80}
          onChange={(event) => setDraft({ ...draft, label: event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="tab-description">Description</label>
        <input
          id="tab-description"
          value={draft.description}
          maxLength={500}
          onChange={(event) => setDraft({ ...draft, description: event.target.value })}
        />
      </div>
      <div className="field span-2">
        <label htmlFor="tab-page">Custom page</label>
        <select id="tab-page" value={draft.page} onChange={(event) => setDraft({ ...draft, page: event.target.value })}>
          {existing ? <option value="">None</option> : <option value={NEW_PAGE}>A new custom page from a script file</option>}
          {pageChoices}
        </select>
      </div>
      {!existing && draft.page === NEW_PAGE ? (
        <>
          <div className="field span-2">
            <label htmlFor="tab-script">Script file</label>
            <input
              id="tab-script"
              type="file"
              accept=".js,text/javascript"
              onChange={(event) => setDraft({ ...draft, file: event.target.files?.[0] })}
              aria-describedby="tab-script-hint"
            />
            <p className="hint" id="tab-script-hint">
              The script is stored by the platform and shown to members inside a sandbox in their browser, with their own
              permissions. The platform never runs it on its servers. The new custom page takes the name and label of the tab.
            </p>
          </div>
          <div className="field span-2">
            <label htmlFor="tab-backend">Reference for server code (optional)</label>
            <input
              id="tab-backend"
              value={draft.backend}
              maxLength={200}
              onChange={(event) => setDraft({ ...draft, backend: event.target.value })}
              aria-describedby="tab-backend-hint"
            />
            <p className="hint" id="tab-backend-hint">
              Kept for later. Nothing runs because of it today.
            </p>
          </div>
        </>
      ) : null}
    </>
  );

  if (screen.creating && tabs) {
    const cancel = () => {
      setDraft(EMPTY);
      screen.back();
    };
    return (
      <DetailPage
        listLabel="Tabs"
        onBack={cancel}
        title="New tab"
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="tab-form" className="button" disabled={busy}>
              Add the tab
            </button>
          </>
        }
      >
        <form id="tab-form" onSubmit={create} noValidate aria-label="New tab">
          <DetailSection title="Detail">
            <DetailGrid>{fields(undefined)}</DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const tab = tabs?.find((candidate) => candidate.apiName === screen.openId);
  if (screen.openId && tabs && !tab) {
    return <MissingDetail listLabel="Tabs" onBack={() => screen.back()} what="This tab does not exist any more." />;
  }

  if (tab) {
    const pageLabel = tab.definition.customPage
      ? (pageLabels.get(tab.definition.customPage) ?? `${tab.definition.customPage} (not found)`)
      : "None";
    return (
      <DetailPage
        listLabel="Tabs"
        onBack={() => screen.back()}
        title={tab.label}
        notices={messages}
        testId="tab-detail"
        actions={
          editing ? (
            <>
              <button type="button" className="button button-secondary" onClick={() => setEditing(false)}>
                Cancel
              </button>
              <button type="submit" form="tab-form" className="button" disabled={busy}>
                Save the tab
              </button>
            </>
          ) : (
            <>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => removeTab(tab)}>
                Remove
              </button>
              <button
                type="button"
                className="button"
                disabled={busy}
                onClick={() => {
                  setDraft({
                    ...EMPTY,
                    label: tab.label,
                    name: tab.apiName,
                    description: tab.description,
                    page: tab.definition.customPage ?? "",
                  });
                  setEditing(true);
                }}
              >
                Change
              </button>
            </>
          )
        }
      >
        <form id="tab-form" onSubmit={update(tab)} noValidate aria-label="Change the tab">
          <DetailSection title="Detail">
            <DetailGrid>
              {editing ? (
                fields(tab)
              ) : (
                <>
                  <DetailItem label="Name" tone="mono">
                    {tab.apiName}
                  </DetailItem>
                  <DetailItem label="Label">{tab.label}</DetailItem>
                  <DetailItem label="Description" wide tone={tab.description ? undefined : "muted"}>
                    {tab.description || "—"}
                  </DetailItem>
                  <DetailItem label="Custom page">{pageLabel}</DetailItem>
                </>
              )}
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const customPages = (
    <div data-testid="custom-pages">
      <DetailSection title="Custom pages">
        <p className="hint">
          The scripts behind the custom tabs. A page can be shown through more than one tab. A page that a tab or a record
          page still uses cannot be removed; the API names what uses it.
        </p>
        {pages.length === 0 ? (
          <p className="dl-value muted">No custom pages yet. Making a tab with a script file makes one.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Custom page</th>
                <th>Script</th>
                <th>Tabs</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {pages.map((page) => (
                <tr key={page.apiName} data-testid="custom-page-row">
                  <td>
                    {page.label}
                    <div className="hint">{page.apiName}</div>
                  </td>
                  <td>
                    <code>{page.definition.frontend.sha256.slice(0, 10)}</code>, {readableSize(page.definition.frontend.size)}
                  </td>
                  <td>{usedBy(page)}</td>
                  <td>
                    {replacing === page.apiName ? (
                      <>
                        <label className="visually-hidden" htmlFor={`replace-${page.apiName}`}>
                          New script for {page.label}
                        </label>
                        <input
                          id={`replace-${page.apiName}`}
                          type="file"
                          accept=".js,text/javascript"
                          onChange={(event) => {
                            const file = event.target.files?.[0];
                            if (file) {
                              replaceScript(page, file);
                            }
                          }}
                        />{" "}
                        <button type="button" className="link-button" onClick={() => setReplacing(undefined)}>
                          Cancel
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => setReplacing(page.apiName)}
                        >
                          Replace the script
                        </button>{" "}
                        <button type="button" className="link-button" disabled={busy} onClick={() => removePage(page)}>
                          Remove
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </DetailSection>
    </div>
  );

  return (
    <ListPage<Row>
      title="Tabs"
      items={rows}
      failure={failure}
      rowKey={(row) => row.key}
      primaryHeader="Tab"
      primary={(row) => row.label}
      primaryExtra={(row) => row.tab?.apiName ?? null}
      canOpen={(row) => Boolean(row.tab)}
      onOpen={(row) => {
        setEditing(false);
        screen.open(row.tab?.apiName ?? "");
      }}
      columns={[
        { key: "source", header: "Source", kind: "muted", cell: (row) => row.source },
        {
          key: "type",
          header: "Type",
          kind: "tag",
          cell: (row) => <Tag tone={row.kind === "Object" ? "on" : "quiet"}>{row.kind}</Tag>,
        },
      ]}
      searchText={(row) => `${row.label} ${row.kind} ${row.source}`}
      onNew={
        offersManage
          ? () => {
              setDraft(EMPTY);
              screen.create();
            }
          : undefined
      }
      newLabel="New tab"
      notices={messages}
      below={failure ? null : customPages}
      emptyText="There are no tabs yet."
      testId="tabs"
      rowTestId="tab-row"
    />
  );
}
