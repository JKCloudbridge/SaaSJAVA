"use client";

import { useCallback, useEffect, useState } from "react";
import { ActionMessages } from "@/components/console/useAction";
import { DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type Template = components["schemas"]["NotificationTemplateView"];
type TemplatesView = components["schemas"]["NotificationTemplatesView"];
type CheckView = components["schemas"]["NotificationTemplateCheckView"];

/** What a person has typed for one kind and has not saved. */
interface Draft {
  title: string;
  body: string;
}

/**
 * The text of the notices and mails the platform sends, which an administrator may rewrite (Sprint 25, ADR-0155). Each kind
 * the API lists is a card: the text in use, the placeholders the kind allows (a click adds one), the platform's own text to
 * go back to, a check that shows the text with sample labels and every problem with its place, and Save. The page names
 * no kind, no placeholder and no limit: they are the API's list, so a kind the server adds is here the day it ships. It
 * checks nothing itself: the rules are the server's, and its words come back for a refusal. With a change set chosen the
 * change is drafted instead of made live.
 */
export function NotificationTextPanel() {
  const { id: workingSet } = useWorkingSet();
  const [view, setView] = useState<TemplatesView | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [checks, setChecks] = useState<Record<string, CheckView>>({});

  const load = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/notification-templates");
      if (!data) {
        setFailure(problemText(error, response));
        return;
      }
      setFailure(undefined);
      setView(data.data);
      setDrafts({});
      setChecks({});
    } catch {
      setFailure("The page could not reach the platform. Try again.");
    }
  }, []);
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);
  const screen = useListScreen(load);
  const { busy, notice, problem, act } = screen;

  const draftOf = (template: Template): Draft => drafts[template.kind] ?? { title: template.title, body: template.body };
  const edit = (template: Template, change: Partial<Draft>) => {
    setChecks((known) => Object.fromEntries(Object.entries(known).filter(([kind]) => kind !== template.kind)));
    setDrafts((known) => ({ ...known, [template.kind]: { ...draftOf(template), ...change } }));
  };

  async function check(template: Template) {
    const draft = draftOf(template);
    const { data, error, response } = await api.POST("/api/v1/metadata/notification-templates/check", {
      body: { kind: template.kind, title: draft.title, body: draft.body },
    });
    if (data) {
      setChecks((known) => ({ ...known, [template.kind]: data.data }));
    } else {
      setChecks((known) => ({ ...known, [template.kind]: { problems: { check: [problemText(error, response)] } } }));
    }
  }

  function save(template: Template) {
    const draft = draftOf(template);
    void act(async () => {
      if (workingSet) {
        const result = template.custom
          ? await addToSet(workingSet, {
              kind: "UPDATE_NOTIFICATION_TEMPLATE",
              itemApiName: template.kind,
              updateNotificationTemplate: { title: draft.title, body: draft.body, version: template.version ?? 0 },
            })
          : await addToSet(workingSet, {
              kind: "CREATE_NOTIFICATION_TEMPLATE",
              createNotificationTemplate: { kind: template.kind, title: draft.title, body: draft.body },
            });
        return { error: result.error, response: result.response, text: DRAFTED_TEXT, problem: problemText(result.error, result.response) };
      }
      const result = template.custom
        ? await api.PUT("/api/v1/metadata/notification-templates/{kind}", {
            params: { path: { kind: template.kind } },
            body: { title: draft.title, body: draft.body, version: template.version ?? 0 },
          })
        : await api.POST("/api/v1/metadata/notification-templates", {
            body: { kind: template.kind, title: draft.title, body: draft.body },
          });
      return { error: result.error, response: result.response, text: "The text was saved.", problem: problemText(result.error, result.response) };
    });
  }

  function reset(template: Template) {
    void act(async () => {
      const result = workingSet
        ? await addToSet(workingSet, { kind: "DELETE_NOTIFICATION_TEMPLATE", itemApiName: template.kind })
        : await api.DELETE("/api/v1/metadata/notification-templates/{kind}", { params: { path: { kind: template.kind } } });
      return {
        error: result.error,
        response: result.response,
        text: workingSet ? DRAFTED_TEXT : "The platform's own text is in use again.",
        problem: problemText(result.error, result.response),
      };
    });
  }


  const messages = <ActionMessages notice={notice} problem={problem} testId="notification-text" />;
  const template = view?.templates.find((candidate) => candidate.kind === screen.openId);

  if (screen.openId && view && !template) {
    return (
      <MissingDetail listLabel="Notification text" onBack={() => screen.back()} what="This kind of notice does not exist." />
    );
  }

  if (view && template) {
    const draft = draftOf(template);
    const checked = checks[template.kind];
    const places = checked ? Object.entries(checked.problems) : [];
    return (
      <DetailPage
        listLabel="Notification text"
        onBack={() => screen.back()}
        title={template.summary}
        notices={messages}
        testId={`template-${template.kind}`}
        actions={
          <>
            {template.custom ? (
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => reset(template)}>
                Go back to the platform&apos;s text
              </button>
            ) : (
              <button
                type="button"
                className="button button-secondary"
                onClick={() => edit(template, { title: template.defaultTitle, body: template.defaultBody })}
              >
                Start from the platform&apos;s text
              </button>
            )}
            <button type="button" className="button button-secondary" disabled={busy} onClick={() => void check(template)}>
              Check
            </button>
            <button type="button" className="button" disabled={busy} onClick={() => save(template)}>
              Save
            </button>
          </>
        }
      >
        <DetailSection title="The words">
          <div className="stack">
            <p className="hint">
              <Tag tone={template.custom ? "on" : "quiet"}>{template.custom ? "Your text" : "Platform text"}</Tag> Kind:{" "}
              <code>{template.kind}</code>, category {template.category}.
            </p>
            <div className="field">
              <label htmlFor={`title-${template.kind}`}>Title</label>
              <input
                id={`title-${template.kind}`}
                value={draft.title}
                maxLength={view.titleMax}
                onChange={(event) => edit(template, { title: event.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor={`body-${template.kind}`}>Text</label>
              <textarea
                id={`body-${template.kind}`}
                rows={4}
                value={draft.body}
                maxLength={view.bodyMax}
                onChange={(event) => edit(template, { body: event.target.value })}
              />
            </div>
          </div>
        </DetailSection>
        <DetailSection title="Placeholders">
          <div className="filters-actions" role="group" aria-label="Placeholders">
            {template.placeholders.map((one) => (
              <button
                key={one.name}
                type="button"
                className="link-button"
                title={one.help}
                onClick={() => edit(template, { body: `${draft.body}{${one.name}}` })}
              >
                {`{${one.name}}`}
              </button>
            ))}
          </div>
        </DetailSection>
        {places.length > 0 || checked ? (
          <DetailSection title="Check">
            {places.length > 0 ? (
              <ul className="form-message" role="alert" data-testid={`problems-${template.kind}`}>
                {places.flatMap(([place, texts]) =>
                  texts.map((text) => <li key={`${place}-${text}`}>{`${place}: ${text}`}</li>),
                )}
              </ul>
            ) : null}
            {checked && places.length === 0 ? (
              <div data-testid={`preview-${template.kind}`}>
                <p className="hint">How it reads, with sample labels:</p>
                <p>
                  <strong>{checked.previewTitle}</strong>
                </p>
                <p>{checked.previewBody}</p>
              </div>
            ) : null}
          </DetailSection>
        ) : null}
      </DetailPage>
    );
  }

  return (
    <ListPage<Template>
      title="Notification text"
      intro="The sentences the platform sends to members, in the application and by mail. A placeholder stands for a label of your organization (never for anything in a record), and a text holds no link: the platform adds its own. Notices already made show the new words the next time they are read."
      items={view?.templates}
      failure={failure}
      rowKey={(row) => row.kind}
      primaryHeader="Kind"
      primary={(row) => row.summary}
      primaryExtra={(row) => row.kind}
      onOpen={(row) => screen.open(row.kind)}
      columns={[
        { key: "category", header: "Category", kind: "muted", cell: (row) => row.category },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => <Tag tone={row.custom ? "on" : "quiet"}>{row.custom ? "Your text" : "Platform text"}</Tag>,
        },
      ]}
      searchText={(row) => `${row.summary} ${row.kind} ${row.category}`}
      notices={messages}
      emptyText="There are no kinds of notice."
      testId="notification-text"
      rowTestId="template-row"
    />
  );
}
