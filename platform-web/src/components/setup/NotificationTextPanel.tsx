"use client";

import { useCallback, useEffect, useState } from "react";
import { ActionMessages, useAction } from "@/components/console/useAction";
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
  const { busy, notice, problem, act } = useAction(load);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="notification-text-failed">
        {failure}
      </p>
    );
  }
  if (!view) {
    return <p aria-live="polite">Loading…</p>;
  }

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

  return (
    <section aria-labelledby="notification-text-heading" className="stack" data-testid="notification-text">
      <h2 id="notification-text-heading">Notification text</h2>
      <p className="hint">
        The sentences the platform sends to members, in the application and by mail. A placeholder stands for a label of your
        organization (never for anything in a record), and a text holds no link: the platform adds its own. Notices already
        made show the new words the next time they are read.
      </p>
      <ActionMessages notice={notice} problem={problem} testId="notification-text" />
      {view.templates.map((template) => {
        const draft = draftOf(template);
        const checked = checks[template.kind];
        const places = checked ? Object.entries(checked.problems) : [];
        return (
          <article key={template.kind} className="card stack" data-testid={`template-${template.kind}`}>
            <div className="list-head">
              <h3>{template.summary}</h3>
              <span className="badge">{template.custom ? "Your text" : "Platform text"}</span>
            </div>
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
            {places.length > 0 ? (
              <ul className="form-message" role="alert" data-testid={`problems-${template.kind}`}>
                {places.flatMap(([place, texts]) => texts.map((text) => <li key={`${place}-${text}`}>{`${place}: ${text}`}</li>))}
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
            <div className="filters-actions" role="group" aria-label="Actions">
              <button type="button" className="button button-secondary" disabled={busy} onClick={() => void check(template)}>
                Check
              </button>
              <button type="button" className="button" disabled={busy} onClick={() => save(template)}>
                Save
              </button>
              {template.custom ? (
                <button type="button" className="button button-secondary" disabled={busy} onClick={() => reset(template)}>
                  Go back to the platform&apos;s text
                </button>
              ) : (
                <button
                  type="button"
                  className="link-button"
                  onClick={() => edit(template, { title: template.defaultTitle, body: template.defaultBody })}
                >
                  Start from the platform&apos;s text
                </button>
              )}
            </div>
          </article>
        );
      })}
    </section>
  );
}
