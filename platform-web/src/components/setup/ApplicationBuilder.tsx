"use client";

import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { BuilderShell } from "@/components/builder/BuilderShell";
import { DropZone } from "@/components/builder/DropZone";
import { MoveButtons } from "@/components/builder/MoveButtons";
import { Palette } from "@/components/builder/Palette";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { startDrag, type DragPayload } from "@/lib/builder/dragPayload";
import {
  changeEntry,
  entriesFromItems,
  headingEntry,
  insertEntry,
  isListed,
  itemsFromEntries,
  linkEntry,
  moveBefore,
  nudgeEntry,
  objectEntry,
  recordPageEntry,
  removeEntry,
  tabEntry,
  unfinished,
  type NavEntry,
} from "@/lib/builder/navigationModel";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ApplicationView = components["schemas"]["ApplicationView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];
type ProfileView = components["schemas"]["ProfileView"];
type TabView = components["schemas"]["TabView"];
type RecordPageView = components["schemas"]["RecordPageView"];

const NAV = "nav";
const KIND_TEXT: Record<NavEntry["type"], string> = {
  OBJECT: "Object",
  TAB: "Tab",
  HEADING: "Heading",
  LINK: "Link",
  RECORD_PAGE: "Record page",
};

interface Identity {
  name: string;
  label: string;
  description: string;
  iconKey: string;
  profiles: string[];
}

const EMPTY: Identity = { name: "", label: "", description: "", iconKey: "", profiles: [] };

/**
 * The application builder: the settings of an application (name, icon, the profiles that see it) and its navigation,
 * built by dragging objects and tabs from the palette into one ordered list (or adding them with the keyboard), where each
 * entry can be ordered, given its own label, or taken out. Headings, links and record pages are entries too, finished in
 * place. The menu members see comes from what is saved here; the browser decides nothing about it. The API checks that
 * what an entry opens exists and that a link stays inside the organization, and its words are shown as they are.
 */
export function ApplicationBuilder() {
  const params = useParams<{ applicationApiName: string }>();
  const applicationApiName = params.applicationApiName;
  const creating = applicationApiName === "new";
  const router = useRouter();
  const { id: workingSet } = useWorkingSet();
  const [application, setApplication] = useState<ApplicationView | undefined>();
  const [objects, setObjects] = useState<ObjectSummary[] | undefined>();
  const [profiles, setProfiles] = useState<ProfileView[]>([]);
  const [tabs, setTabs] = useState<TabView[]>([]);
  const [pagesOf, setPagesOf] = useState<Record<string, RecordPageView[]>>({});
  const [failure, setFailure] = useState<string | undefined>();
  const [identity, setIdentity] = useState<Identity>(EMPTY);
  const [entries, setEntries] = useState<NavEntry[]>([]);
  const [baseline, setBaseline] = useState("");

  const snapshot = useCallback(
    (who: Identity, list: readonly NavEntry[]) => JSON.stringify([who, itemsFromEntries(list)]),
    [],
  );

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
      // The entry then offers no pages; choosing the object again tries once more.
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const [objectList, profileList, tabList] = await Promise.all([
        api.GET("/api/v1/metadata/objects"),
        api.GET("/api/v1/profiles"),
        api.GET("/api/v1/metadata/tabs"),
      ]);
      if (!objectList.data) {
        setFailure(await failureText(objectList.error, objectList.response));
        return;
      }
      setObjects(objectList.data.data);
      setProfiles(profileList.data?.data ?? []);
      setTabs(tabList.data?.data ?? []);
      if (creating) {
        setIdentity(EMPTY);
        setEntries([]);
        setBaseline(snapshot(EMPTY, []));
        return;
      }
      const found = await api.GET("/api/v1/metadata/applications/{applicationApiName}", {
        params: { path: { applicationApiName } },
      });
      if (!found.data) {
        setFailure(await failureText(found.error, found.response));
        return;
      }
      const view = found.data.data;
      const who: Identity = {
        name: view.apiName,
        label: view.label,
        description: view.description,
        iconKey: view.definition.iconKey ?? "",
        profiles: view.definition.profiles ?? [],
      };
      const list = entriesFromItems(view.definition.items);
      setApplication(view);
      setIdentity(who);
      setEntries(list);
      setBaseline(snapshot(who, list));
      setFailure(undefined);
      list.filter((entry) => entry.type === "RECORD_PAGE").forEach((entry) => void ensurePages(entry.object));
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [applicationApiName, creating, snapshot, ensurePages]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const reload = useCallback(async () => {
    if (!creating) {
      await load();
    }
  }, [creating, load]);
  const { busy, notice, problem, act } = useAction(reload);

  const objectLabels = useMemo(() => new Map((objects ?? []).map((one) => [one.apiName, one.label])), [objects]);
  const tabLabels = useMemo(() => new Map(tabs.map((one) => [one.apiName, one.label])), [tabs]);
  const dirty = baseline !== "" && snapshot(identity, entries) !== baseline;
  const open = unfinished(entries);
  const listHref = "/setup/applications";

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="application-builder-failed">
        {failure}
      </p>
    );
  }
  if (!objects) {
    return <p aria-live="polite">Loading…</p>;
  }

  /** The entry a palette chip stands for. */
  function entryFor(key: string): NavEntry | undefined {
    if (key.startsWith("object:")) {
      return objectEntry(key.slice("object:".length));
    }
    if (key.startsWith("tab:")) {
      return tabEntry(key.slice("tab:".length));
    }
    if (key === "heading") {
      return headingEntry();
    }
    if (key === "link") {
      return linkEntry();
    }
    if (key === "record-page") {
      return recordPageEntry();
    }
    return undefined;
  }

  function add(key: string, before?: number) {
    const entry = entryFor(key);
    if (entry) {
      setEntries((current) => insertEntry(current, entry, before));
    }
  }

  function dropAt(payload: DragPayload, before?: number) {
    if (payload.origin === "palette") {
      add(payload.key, before);
    } else if (payload.group === NAV) {
      setEntries((current) => moveBefore(current, payload.index, before));
    }
  }

  function save() {
    const definition = {
      ...(identity.iconKey ? { iconKey: identity.iconKey } : {}),
      profiles: identity.profiles,
      items: itemsFromEntries(entries),
    };
    void act(async () => {
      if (!creating && application) {
        const update = {
          label: identity.label,
          description: identity.description,
          definition,
          version: application.version,
        };
        const result = workingSet
          ? await addToSet(workingSet, {
              kind: "UPDATE_APPLICATION",
              itemApiName: application.apiName,
              updateApplication: update,
            })
          : await api.PUT("/api/v1/metadata/applications/{applicationApiName}", {
              params: { path: { applicationApiName: application.apiName } },
              body: update,
            });
        if (result.response.ok && workingSet) {
          setBaseline(snapshot(identity, entries));
        }
        return {
          error: result.error,
          response: result.response,
          text: workingSet ? DRAFTED_TEXT : "The application was saved.",
          problem: problemText(result.error, result.response),
        };
      }
      const request = { name: identity.name, label: identity.label, description: identity.description, definition };
      if (workingSet) {
        const drafted = await addToSet(workingSet, { kind: "CREATE_APPLICATION", createApplication: request });
        if (drafted.response.ok) {
          setBaseline(snapshot(identity, entries));
          router.replace(listHref);
        }
        return {
          error: drafted.error,
          response: drafted.response,
          text: DRAFTED_TEXT,
          problem: problemText(drafted.error, drafted.response),
        };
      }
      const result = await api.POST("/api/v1/metadata/applications", { body: request });
      if (result.response.ok && result.data) {
        setBaseline(snapshot(identity, entries));
        router.replace(`${listHref}/${encodeURIComponent(result.data.data.apiName)}`);
      }
      return {
        error: result.error,
        response: result.response,
        text: "The application was added.",
        problem: problemText(result.error, result.response),
      };
    });
  }

  const titleOf = (entry: NavEntry): string => {
    switch (entry.type) {
      case "OBJECT":
        return objectLabels.get(entry.object) ?? (entry.object || "Choose an object");
      case "TAB":
        return tabLabels.get(entry.tab) ?? (entry.tab || "Choose a tab");
      case "HEADING":
        return entry.label || "Heading";
      case "LINK":
        return entry.label || "Link";
      default:
        return entry.label || "Record page";
    }
  };

  return (
    <BuilderShell
      testId="application-builder"
      crumbs={[
        { label: "Applications", href: listHref },
        { label: creating ? "New application" : identity.label || applicationApiName },
      ]}
      title={creating ? "New application" : identity.label || applicationApiName}
      backHref={listHref}
      dirty={dirty}
      busy={busy}
      saveLabel={creating ? "Add the application" : "Save the application"}
      onSave={save}
    >
      <ActionMessages notice={notice} problem={problem} testId="application-builder" />
      <section aria-labelledby="application-identity-heading" className="card">
        <h3 id="application-identity-heading">About this application</h3>
        <div className="record-grid record-grid-two">
          {creating ? (
            <div className="field">
              <label htmlFor="application-name">Name</label>
              <input
                id="application-name"
                value={identity.name}
                maxLength={40}
                onChange={(event) => setIdentity({ ...identity, name: event.target.value })}
                aria-describedby="application-name-hint"
              />
              <p className="hint" id="application-name-hint">
                The permanent name, starting with a capital letter, for example Sales. The platform adds the ending __c.
              </p>
            </div>
          ) : (
            <p>
              <code>{application?.apiName}</code>. The name of an application never changes.
            </p>
          )}
          <div className="field">
            <label htmlFor="application-label">Label</label>
            <input
              id="application-label"
              value={identity.label}
              maxLength={80}
              onChange={(event) => setIdentity({ ...identity, label: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="application-description">Description</label>
            <input
              id="application-description"
              value={identity.description}
              maxLength={500}
              onChange={(event) => setIdentity({ ...identity, description: event.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="application-icon">Icon key</label>
            <input
              id="application-icon"
              value={identity.iconKey}
              maxLength={40}
              onChange={(event) => setIdentity({ ...identity, iconKey: event.target.value })}
              aria-describedby="application-icon-hint"
            />
            <p className="hint" id="application-icon-hint">
              Lower case letters, digits and hyphens, for example briefcase. The page shows the icon it knows by that key.
            </p>
          </div>
        </div>
        <fieldset className="field">
          <legend>Seen by the members of these profiles (none chosen: every member)</legend>
          {profiles.map((profile) => (
            <label key={profile.id}>
              <input
                type="checkbox"
                checked={identity.profiles.includes(profile.id)}
                onChange={(event) =>
                  setIdentity({
                    ...identity,
                    profiles: event.target.checked
                      ? [...identity.profiles, profile.id]
                      : identity.profiles.filter((id) => id !== profile.id),
                  })
                }
              />{" "}
              {profile.name}
            </label>
          ))}
        </fieldset>
      </section>

      <section aria-labelledby="application-navigation-heading" className="stack">
        <h3 id="application-navigation-heading">Navigation</h3>
        <p className="hint">
          The menu of the application, in order. Drag objects and tabs in, or choose them in the palette. An object opens its
          list; a tab opens a page the organization made in Setup, Tabs.
        </p>
        <div className="builder">
          <Palette
            label="Navigation parts"
            searchable
            categories={[
              {
                id: "objects",
                label: "Objects",
                empty: "The organization has no object.",
                items: objects.map((one) => ({
                  key: `object:${one.apiName}`,
                  label: one.label,
                  title: `${one.label} (${one.apiName})`,
                  used: isListed(entries, "OBJECT", one.apiName),
                })),
              },
              {
                id: "tabs",
                label: "Custom tabs",
                empty: "The organization has made no tab yet. Make one in Setup, Tabs.",
                items: tabs.map((one) => ({
                  key: `tab:${one.apiName}`,
                  label: one.label,
                  title: `${one.label} (${one.apiName})`,
                  used: isListed(entries, "TAB", one.apiName),
                })),
              },
              {
                id: "other",
                label: "Other",
                empty: "",
                items: [
                  { key: "heading", label: "Heading", title: "A line of text that groups the entries after it" },
                  { key: "link", label: "Link", title: "A page of this organization" },
                  { key: "record-page", label: "Record page", title: "A record page of an object" },
                ],
              },
            ]}
            onPick={(item) => add(item.key)}
          />
          <DropZone
            className="canvas-card"
            aria-label="Navigation in order"
            data-testid="navigation-canvas"
            onDropPayload={(payload: DragPayload) => dropAt(payload)}
          >
            <div className="canvas-card-head">
              <strong>Navigation, in order</strong>
              <span className="hint">{entries.length} {entries.length === 1 ? "entry" : "entries"}</span>
            </div>
            <div className="canvas-items is-list">
              {entries.length === 0 ? (
                <p className="canvas-empty">Nothing yet. Drag an object or a tab here, or choose one in the palette.</p>
              ) : null}
              {entries.map((entry, index) => (
                <DropZone
                  key={entry.id}
                  className={["item-chip", "item-chip-wide", open.includes(index) ? "is-open" : ""]
                    .filter(Boolean)
                    .join(" ")}
                  data-testid="navigation-entry"
                  onDropPayload={(payload: DragPayload) => dropAt(payload, index)}
                >
                  <span
                    className="drag-handle"
                    draggable
                    title="Drag to move the entry"
                    aria-hidden="true"
                    onDragStart={(event) => startDrag(event, { origin: "canvas", group: NAV, index })}
                  >
                    ⠿
                  </span>
                  <span className="entry-fields">
                    <span className="badge">{KIND_TEXT[entry.type]}</span>
                    {entry.type === "OBJECT" || entry.type === "TAB" ? <strong>{titleOf(entry)}</strong> : null}
                    {entry.type === "HEADING" ? (
                      <>
                        <label className="visually-hidden" htmlFor={`entry-label-${entry.id}`}>
                          Text of the heading
                        </label>
                        <input
                          id={`entry-label-${entry.id}`}
                          value={entry.label}
                          maxLength={60}
                          placeholder="Heading"
                          onChange={(event) => setEntries(changeEntry(entries, index, { label: event.target.value }))}
                        />
                      </>
                    ) : null}
                    {entry.type === "LINK" ? (
                      <>
                        <label className="visually-hidden" htmlFor={`entry-path-${entry.id}`}>
                          Path of the link
                        </label>
                        <input
                          id={`entry-path-${entry.id}`}
                          value={entry.path}
                          maxLength={200}
                          placeholder="/members"
                          title="A page of this organization, starting with a slash. Not an address elsewhere."
                          onChange={(event) => setEntries(changeEntry(entries, index, { path: event.target.value }))}
                        />
                        <label className="visually-hidden" htmlFor={`entry-label-${entry.id}`}>
                          Text of the link
                        </label>
                        <input
                          id={`entry-label-${entry.id}`}
                          value={entry.label}
                          maxLength={60}
                          placeholder="Text"
                          onChange={(event) => setEntries(changeEntry(entries, index, { label: event.target.value }))}
                        />
                      </>
                    ) : null}
                    {entry.type === "RECORD_PAGE" ? (
                      <>
                        <label className="visually-hidden" htmlFor={`entry-object-${entry.id}`}>
                          Object of the record page
                        </label>
                        <select
                          id={`entry-object-${entry.id}`}
                          value={entry.object}
                          onChange={(event) => {
                            void ensurePages(event.target.value);
                            setEntries(changeEntry(entries, index, { object: event.target.value, page: "" }));
                          }}
                        >
                          <option value="">Choose an object</option>
                          {objects.map((one) => (
                            <option key={one.apiName} value={one.apiName}>
                              {one.label}
                            </option>
                          ))}
                        </select>
                        <label className="visually-hidden" htmlFor={`entry-page-${entry.id}`}>
                          Record page
                        </label>
                        <select
                          id={`entry-page-${entry.id}`}
                          value={entry.page}
                          onChange={(event) => setEntries(changeEntry(entries, index, { page: event.target.value }))}
                        >
                          <option value="">Choose a record page</option>
                          {(pagesOf[entry.object] ?? []).map((one) => (
                            <option key={one.apiName} value={one.apiName}>
                              {one.label}
                            </option>
                          ))}
                        </select>
                      </>
                    ) : null}
                    {entry.type === "OBJECT" || entry.type === "TAB" || entry.type === "RECORD_PAGE" ? (
                      <>
                        <label className="visually-hidden" htmlFor={`entry-name-${entry.id}`}>
                          Label shown instead of the name (optional)
                        </label>
                        <input
                          id={`entry-name-${entry.id}`}
                          value={entry.label}
                          maxLength={60}
                          placeholder="Label (optional)"
                          onChange={(event) => setEntries(changeEntry(entries, index, { label: event.target.value }))}
                        />
                      </>
                    ) : null}
                    {open.includes(index) ? <span className="badge">Not finished</span> : null}
                  </span>
                  <MoveButtons
                    name={titleOf(entry)}
                    onUp={index > 0 ? () => setEntries(nudgeEntry(entries, index, -1)) : undefined}
                    onDown={index < entries.length - 1 ? () => setEntries(nudgeEntry(entries, index, 1)) : undefined}
                    onRemove={() => setEntries(removeEntry(entries, index))}
                  />
                </DropZone>
              ))}
            </div>
          </DropZone>
        </div>
      </section>
    </BuilderShell>
  );
}
