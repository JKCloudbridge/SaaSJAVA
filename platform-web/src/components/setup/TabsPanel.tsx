"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction, type ActionResult } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { uploadScript } from "@/lib/builder/uploadScript";
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
 * The tabs of the organization. Every object has a tab already (it opens the object's list), so those rows are listed and
 * need nothing made; a custom tab shows a custom page, a script the organization uploads. The script is stored and
 * shown in a sandbox in the member's browser; the platform never runs it on its servers. The page shows what the API
 * answers: that the script is acceptable, and what depends on a tab or a page, is the API's to decide.
 */
export function TabsPanel() {
  const { id: workingSet } = useWorkingSet();
  const [tabs, setTabs] = useState<TabView[] | undefined>();
  const [pages, setPages] = useState<CustomPageView[]>([]);
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const [search, setSearch] = useState("");
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [opening, setOpening] = useState(false);
  const [editing, setEditing] = useState<TabView | undefined>();
  const [replacing, setReplacing] = useState<string | undefined>();

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

  const { busy, notice, problem, act } = useAction(reload);

  const pageLabels = useMemo(() => new Map(pages.map((one) => [one.apiName, one.label])), [pages]);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="tabs-failed">
        {failure}
      </p>
    );
  }
  if (!tabs) {
    return <p aria-live="polite">Loading…</p>;
  }

  const rows: Row[] = [
    ...objects.map(
      (one): Row => ({ key: `object:${one.apiName}`, label: one.label, kind: "Object", source: `${one.label} object: its list` }),
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
  const needle = search.trim().toLowerCase();
  const shown = rows.filter(
    (row) =>
      needle === "" ||
      row.label.toLowerCase().includes(needle) ||
      row.kind.toLowerCase().includes(needle) ||
      row.source.toLowerCase().includes(needle),
  );

  function reset() {
    setDraft(EMPTY);
    setEditing(undefined);
    setOpening(false);
  }

  /** One change, live or into the chosen change set; the first one that fails is the answer. */
  async function send(
    live: () => Promise<{ error?: unknown; response: Response }>,
    drafted: () => Promise<{ error?: unknown; response: Response }>,
  ) {
    return workingSet ? drafted() : live();
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async (): Promise<ActionResult> => {
      if (editing) {
        const definition = { kind: "CUSTOM" as const, customPage: draft.page || undefined };
        const update = { label: draft.label, description: draft.description, definition, version: editing.version };
        const result = await send(
          () =>
            api.PUT("/api/v1/metadata/tabs/{apiName}", {
              params: { path: { apiName: editing.apiName } },
              body: update,
            }),
          () => addToSet(workingSet ?? "", { kind: "UPDATE_TAB", itemApiName: editing.apiName, updateTab: update }),
        );
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The tab was saved.",
          problem: problemText(result.error, result.response),
        };
      }

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
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The tab was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function removeTab(tab: TabView) {
    void act(async () => {
      const result = await send(
        () => api.DELETE("/api/v1/metadata/tabs/{apiName}", { params: { path: { apiName: tab.apiName } } }),
        () => addToSet(workingSet ?? "", { kind: "DELETE_TAB", itemApiName: tab.apiName }),
      );
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
      const update = {
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
            body: update,
          }),
        () => addToSet(workingSet ?? "", { kind: "UPDATE_CUSTOM_PAGE", itemApiName: page.apiName, updateCustomPage: update }),
      );
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The script of the custom page was replaced.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const formName = editing ? "Change the tab" : "New tab";
  const usedBy = (page: CustomPageView) => tabs.filter((tab) => tab.definition.customPage === page.apiName).length;

  return (
    <div className="stack" data-testid="tabs">
      <section aria-labelledby="tabs-heading">
        <div className="list-head">
          <h2 id="tabs-heading">Tabs</h2>
          <div className="search-row">
            <label className="visually-hidden" htmlFor="tabs-search">
              Search tabs
            </label>
            <input
              id="tabs-search"
              type="search"
              placeholder="Search tabs…"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <button
              type="button"
              className="button"
              onClick={() => {
                setEditing(undefined);
                setDraft(EMPTY);
                setOpening(true);
              }}
            >
              New tab
            </button>
          </div>
        </div>
        <p className="hint">
          Every object already has its own tab here: it opens the object&apos;s list. Make a custom tab to show a custom page,
          then place the tab in an application or on a record page.
        </p>
        <ActionMessages notice={notice} problem={problem} testId="tabs" />
        <table className="table">
          <thead>
            <tr>
              <th>Tab</th>
              <th>Type</th>
              <th>Source</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 ? (
              <tr>
                <td colSpan={4}>No tab matches “{search}”.</td>
              </tr>
            ) : (
              shown.map((row) => (
                <tr key={row.key} data-testid="tab-row">
                  <td>
                    {row.label}
                    {row.tab ? <div className="hint">{row.tab.apiName}</div> : null}
                  </td>
                  <td>
                    <span className={row.kind === "Object" ? "badge badge-on" : "badge"}>{row.kind}</span>
                  </td>
                  <td>{row.source}</td>
                  <td>
                    {row.tab ? (
                      <>
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => {
                            const tab = row.tab as TabView;
                            setEditing(tab);
                            setDraft({
                              ...EMPTY,
                              label: tab.label,
                              name: tab.apiName,
                              description: tab.description,
                              page: tab.definition.customPage ?? "",
                            });
                            setOpening(true);
                          }}
                        >
                          Change
                        </button>{" "}
                        <button
                          type="button"
                          className="link-button"
                          disabled={busy}
                          onClick={() => removeTab(row.tab as TabView)}
                        >
                          Remove
                        </button>
                      </>
                    ) : null}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>

      {opening ? (
        <section aria-labelledby="tab-form-heading" className="card">
          <h3 id="tab-form-heading">{formName}</h3>
          <form className="form" onSubmit={save} noValidate aria-label={formName}>
            {editing ? (
              <p>
                <code>{editing.apiName}</code>. The name of a tab never changes.
              </p>
            ) : (
              <div className="field">
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
            <div className="field">
              <label htmlFor="tab-page">Custom page</label>
              <select id="tab-page" value={draft.page} onChange={(event) => setDraft({ ...draft, page: event.target.value })}>
                {editing ? <option value="">None</option> : <option value={NEW_PAGE}>A new custom page from a script file</option>}
                {pages.map((one) => (
                  <option key={one.apiName} value={one.apiName}>
                    {one.label} ({one.apiName})
                  </option>
                ))}
              </select>
            </div>
            {!editing && draft.page === NEW_PAGE ? (
              <>
                <div className="field">
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
                    permissions. The platform never runs it on its servers. The new custom page takes the name and label of
                    the tab.
                  </p>
                </div>
                <div className="field">
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
            <div className="form-actions">
              <button type="submit" className="button" disabled={busy}>
                {editing ? "Save the tab" : "Add the tab"}
              </button>
              <button type="button" className="link-button" onClick={reset}>
                Cancel
              </button>
            </div>
          </form>
        </section>
      ) : null}

      <section aria-labelledby="custom-pages-heading" data-testid="custom-pages">
        <h3 id="custom-pages-heading">Custom pages</h3>
        <p className="hint">
          The scripts behind the custom tabs. A page can be shown through more than one tab. A page that a tab or a record
          page still uses cannot be removed; the API names what uses it.
        </p>
        {pages.length === 0 ? (
          <p>No custom pages yet. Making a tab with a script file makes one.</p>
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
      </section>
    </div>
  );
}
