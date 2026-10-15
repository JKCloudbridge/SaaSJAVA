"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ApplicationView = components["schemas"]["ApplicationView"];
type ApplicationDefinition = components["schemas"]["ApplicationDefinition"];
type NavigationItem = components["schemas"]["NavigationItem"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type ProfileView = components["schemas"]["ProfileView"];
type TabView = components["schemas"]["TabView"];
type RecordPageView = components["schemas"]["RecordPageView"];

interface ItemDraft {
  type: NavigationItem["type"];
  label: string;
  object: string;
  page: string;
  path: string;
  tab: string;
}

interface Draft {
  name: string;
  label: string;
  description: string;
  iconKey: string;
  profiles: string[];
  items: ItemDraft[];
}

const EMPTY: Draft = { name: "", label: "", description: "", iconKey: "", profiles: [], items: [] };

const TYPE_TEXT: Record<NavigationItem["type"], string> = {
  OBJECT: "An object",
  RECORD_PAGE: "A record page",
  LINK: "A link inside the organization",
  HEADING: "A heading",
  TAB: "A tab",
};

function draftOf(application: ApplicationView): Draft {
  return {
    name: application.apiName,
    label: application.label,
    description: application.description,
    iconKey: application.definition.iconKey ?? "",
    profiles: application.definition.profiles ?? [],
    items: application.definition.items.map((item) => ({
      type: item.type,
      label: item.label ?? "",
      object: item.object ?? "",
      page: item.page ?? "",
      path: item.path ?? "",
      tab: item.tab ?? "",
    })),
  };
}

function itemOf(draft: ItemDraft): NavigationItem {
  const item: NavigationItem = { type: draft.type };
  if (draft.label) {
    item.label = draft.label;
  }
  if (draft.type === "OBJECT" || draft.type === "RECORD_PAGE") {
    item.object = draft.object;
  }
  if (draft.type === "RECORD_PAGE") {
    item.page = draft.page;
  }
  if (draft.type === "LINK") {
    item.path = draft.path;
  }
  if (draft.type === "TAB") {
    item.tab = draft.tab;
  }
  return item;
}

function definitionOf(draft: Draft): ApplicationDefinition {
  return {
    ...(draft.iconKey ? { iconKey: draft.iconKey } : {}),
    profiles: draft.profiles,
    items: draft.items.map(itemOf),
  };
}

function move<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length) {
    return list;
  }
  const next = [...list];
  const [taken] = next.splice(from, 1);
  next.splice(to, 0, taken as T);
  return next;
}

/**
 * The applications of the organization: a name, an icon, the profiles whose members see it (none chosen: everyone) and
 * the navigation in order. A navigation item opens an object, a record page, a tab, a heading or a path inside the
 * organization, never an address elsewhere. (A tab is made elsewhere; this list only places it. Sprint 21 builds the
 * screen that makes tabs and custom pages.) They are made, changed and removed here live or, when a change set is chosen, as drafts. The
 * page shows what the API answers: that what an item opens exists, and that a link stays inside the organization, is the
 * API's to decide.
 */
export function ApplicationsPanel() {
  const { id: workingSet } = useWorkingSet();
  const [applications, setApplications] = useState<ApplicationView[] | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [profiles, setProfiles] = useState<ProfileView[]>([]);
  const [tabs, setTabs] = useState<TabView[]>([]);
  const [pagesOf, setPagesOf] = useState<Record<string, RecordPageView[]>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState<ApplicationView | undefined>();

  const ensurePages = useCallback(async (objectApiName: string) => {
    if (!objectApiName) {
      return;
    }
    try {
      const { data } = await api.GET("/api/v1/metadata/objects/{objectApiName}/record-pages", {
        params: { path: { objectApiName } },
      });
      if (data) {
        setPagesOf((known) => ({ ...known, [objectApiName]: data.data }));
      }
    } catch {
      // The item then offers no pages; choosing the object again tries once more.
    }
  }, []);

  const reload = useCallback(async () => {
    try {
      const [list, objectList, profileList, tabList] = await Promise.all([
        api.GET("/api/v1/metadata/applications"),
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/profiles"),
        api.GET("/api/v1/metadata/tabs"),
      ]);
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setApplications(list.data.data);
      setObjects(objectList.data?.data ?? []);
      setProfiles(profileList.data?.data ?? []);
      setTabs(tabList.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="applications-failed">
        {failure}
      </p>
    );
  }
  if (!applications) {
    return <p aria-live="polite">Loading…</p>;
  }

  function reset() {
    setDraft(EMPTY);
    setEditing(undefined);
  }

  function changeItem(index: number, change: Partial<ItemDraft>) {
    setDraft({ ...draft, items: draft.items.map((item, at) => (at === index ? { ...item, ...change } : item)) });
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const definition = definitionOf(draft);
    void act(async () => {
      if (editing) {
        const update = { label: draft.label, description: draft.description, definition, version: editing.version };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_APPLICATION",
              itemApiName: editing.apiName,
              updateApplication: update,
            })
          : await api.PUT("/api/v1/metadata/applications/{applicationApiName}", {
              params: { path: { applicationApiName: editing.apiName } },
              body: update,
            });
        if (result.response.ok) {
          reset();
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The application was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = { name: draft.name, label: draft.label, description: draft.description, definition };
      const result = workingSet
        ? await addToSet(workingSet, { kind: "CREATE_APPLICATION", createApplication: request })
        : await api.POST("/api/v1/metadata/applications", { body: request });
      if (result.response.ok) {
        reset();
      }
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The application was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  function remove(application: ApplicationView) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_APPLICATION", itemApiName: application.apiName })
        : await api.DELETE("/api/v1/metadata/applications/{applicationApiName}", {
            params: { path: { applicationApiName: application.apiName } },
          });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The application was removed.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const formName = editing ? "Change the application" : "Add an application";
  const profileName = (id: string) => profiles.find((profile) => profile.id === id)?.name ?? "A profile that was deleted";

  return (
    <section aria-labelledby="applications-heading" data-testid="applications">
      <h2 id="applications-heading">Applications</h2>
      <p className="hint">
        An application is a menu of the organization: the objects, record pages and links its members see. Members see an
        application when it names their profile, or when it names no profile.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="applications" />
      {applications.length === 0 ? (
        <p>No applications yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Application</th>
              <th>API name</th>
              <th>Seen by</th>
              <th>Items</th>
              <th>
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {applications.map((application) => (
              <tr key={application.apiName} data-testid="application-row">
                <td>
                  {application.label}
                  {application.description ? <div className="hint">{application.description}</div> : null}
                </td>
                <td>
                  <code>{application.apiName}</code>
                </td>
                <td>
                  {(application.definition.profiles ?? []).length === 0
                    ? "Every member"
                    : (application.definition.profiles ?? []).map(profileName).join(", ")}
                </td>
                <td>{application.definition.items.length}</td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    disabled={busy}
                    onClick={() => {
                      setEditing(application);
                      setDraft(draftOf(application));
                      application.definition.items.forEach((item) => void ensurePages(item.object ?? ""));
                    }}
                  >
                    Change
                  </button>{" "}
                  <button type="button" className="link-button" disabled={busy} onClick={() => remove(application)}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3>{formName}</h3>
      <form className="form" onSubmit={save} noValidate aria-label={formName}>
        {editing ? (
          <p>
            <code>{editing.apiName}</code>. The name of an application never changes.
          </p>
        ) : (
          <div className="field">
            <label htmlFor="application-name">Name</label>
            <input
              id="application-name"
              value={draft.name}
              maxLength={40}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              required
              aria-describedby="application-name-hint"
            />
            <p className="hint" id="application-name-hint">
              The permanent name, starting with a capital letter, for example Sales. The platform adds the ending __c.
            </p>
          </div>
        )}
        <div className="field">
          <label htmlFor="application-label">Label</label>
          <input
            id="application-label"
            value={draft.label}
            maxLength={80}
            onChange={(event) => setDraft({ ...draft, label: event.target.value })}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="application-description">Description</label>
          <input
            id="application-description"
            value={draft.description}
            maxLength={500}
            onChange={(event) => setDraft({ ...draft, description: event.target.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="application-icon">Icon key</label>
          <input
            id="application-icon"
            value={draft.iconKey}
            maxLength={40}
            onChange={(event) => setDraft({ ...draft, iconKey: event.target.value })}
            aria-describedby="application-icon-hint"
          />
          <p className="hint" id="application-icon-hint">
            Lower case letters, digits and hyphens, for example briefcase. The page shows the icon it knows by that key.
          </p>
        </div>
        <fieldset className="field">
          <legend>Seen by the members of these profiles (none chosen: every member)</legend>
          {profiles.map((profile) => (
            <label key={profile.id}>
              <input
                type="checkbox"
                checked={draft.profiles.includes(profile.id)}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    profiles: event.target.checked
                      ? [...draft.profiles, profile.id]
                      : draft.profiles.filter((id) => id !== profile.id),
                  })
                }
              />{" "}
              {profile.name}
            </label>
          ))}
        </fieldset>

        {draft.items.map((item, index) => (
          <fieldset className="field" key={index} data-testid="application-item">
            <legend>Item {index + 1}</legend>
            <div className="field">
              <label htmlFor={`item-type-${index}`}>Opens</label>
              <select
                id={`item-type-${index}`}
                value={item.type}
                onChange={(event) => changeItem(index, { type: event.target.value as NavigationItem["type"] })}
              >
                {(Object.keys(TYPE_TEXT) as NavigationItem["type"][]).map((type) => (
                  <option key={type} value={type}>
                    {TYPE_TEXT[type]}
                  </option>
                ))}
              </select>
            </div>
            {item.type === "OBJECT" || item.type === "RECORD_PAGE" ? (
              <div className="field">
                <label htmlFor={`item-object-${index}`}>Object</label>
                <select
                  id={`item-object-${index}`}
                  value={item.object}
                  onChange={(event) => {
                    void ensurePages(event.target.value);
                    changeItem(index, { object: event.target.value, page: "" });
                  }}
                >
                  <option value="">Choose an object</option>
                  {objects.map((object) => (
                    <option key={object.apiName} value={object.apiName}>
                      {object.label}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            {item.type === "RECORD_PAGE" ? (
              <div className="field">
                <label htmlFor={`item-page-${index}`}>Record page</label>
                <select
                  id={`item-page-${index}`}
                  value={item.page}
                  onChange={(event) => changeItem(index, { page: event.target.value })}
                >
                  <option value="">Choose a record page</option>
                  {(pagesOf[item.object] ?? []).map((page) => (
                    <option key={page.apiName} value={page.apiName}>
                      {page.label}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            {item.type === "TAB" ? (
              <div className="field">
                <label htmlFor={`item-tab-${index}`}>Tab</label>
                <select
                  id={`item-tab-${index}`}
                  value={item.tab}
                  onChange={(event) => changeItem(index, { tab: event.target.value })}
                >
                  <option value="">Choose a tab</option>
                  {tabs.map((tab) => (
                    <option key={tab.apiName} value={tab.apiName}>
                      {tab.label}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            {item.type === "LINK" ? (
              <div className="field">
                <label htmlFor={`item-path-${index}`}>Path</label>
                <input
                  id={`item-path-${index}`}
                  value={item.path}
                  maxLength={200}
                  onChange={(event) => changeItem(index, { path: event.target.value })}
                  aria-describedby={`item-path-hint-${index}`}
                />
                <p className="hint" id={`item-path-hint-${index}`}>
                  A page of this organization, starting with a slash, for example /members. Not an address elsewhere.
                </p>
              </div>
            ) : null}
            <div className="field">
              <label htmlFor={`item-label-${index}`}>
                Label{item.type === "LINK" || item.type === "HEADING" ? "" : " (leave empty to use the name)"}
              </label>
              <input
                id={`item-label-${index}`}
                value={item.label}
                maxLength={60}
                onChange={(event) => changeItem(index, { label: event.target.value })}
              />
            </div>
            <button
              type="button"
              className="link-button"
              disabled={index === 0}
              onClick={() => setDraft({ ...draft, items: move(draft.items, index, index - 1) })}
            >
              Move item {index + 1} up
            </button>{" "}
            <button
              type="button"
              className="link-button"
              disabled={index === draft.items.length - 1}
              onClick={() => setDraft({ ...draft, items: move(draft.items, index, index + 1) })}
            >
              Move item {index + 1} down
            </button>{" "}
            <button
              type="button"
              className="link-button"
              onClick={() => setDraft({ ...draft, items: draft.items.filter((_, at) => at !== index) })}
            >
              Take out item {index + 1}
            </button>
          </fieldset>
        ))}
        <p>
          <button
            type="button"
            className="link-button"
            onClick={() =>
              setDraft({
                ...draft,
                items: [...draft.items, { type: "OBJECT", label: "", object: "", page: "", path: "", tab: "" }],
              })
            }
          >
            Add an item
          </button>
        </p>
        <div>
          <button type="submit" className="button" disabled={busy}>
            {editing ? "Save the application" : "Add the application"}
          </button>{" "}
          {editing ? (
            <button type="button" className="link-button" onClick={reset}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>
    </section>
  );
}
