"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import { DetailGrid, DetailPage, DetailSection } from "@/components/ui/lists/DetailPage";
import { objectsText, parseObjects, type Webhook, type WebhookCatalogue } from "@/lib/webhooks/model";

export interface WebhookFormValues {
  name: string;
  address: string;
  events: string[];
  objects: string[] | undefined;
}

interface FormProps {
  title: string;
  submitLabel: string;
  catalogue: WebhookCatalogue;
  /** The webhook being changed; without it the form makes a new one. */
  initial?: Webhook;
  busy: boolean;
  messages: ReactNode;
  onCancel: () => void;
  onSubmit: (values: WebhookFormValues) => void;
}

/**
 * The form of a webhook (Sprint 32, ADR-0274): what it is called, the address of the organization's own software, the
 * events it asks for and the objects it is limited to. The events, the limits and the words about whom it acts as come from
 * the API's catalogue: the form holds no list of its own and decides nothing, and the server checks every field and says why
 * one is refused.
 */
export function WebhookForm({ title, submitLabel, catalogue, initial, busy, messages, onCancel, onSubmit }: FormProps) {
  const [name, setName] = useState(initial?.name ?? "");
  const [address, setAddress] = useState(initial?.address ?? "");
  const [events, setEvents] = useState<string[]>(initial?.events ?? []);
  const [objects, setObjects] = useState(objectsText(initial?.objects));

  const toggle = (key: string, on: boolean) =>
    setEvents((current) => (on ? [...current, key] : current.filter((one) => one !== key)));

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSubmit({
      name: name.trim(),
      address: address.trim(),
      events: catalogue.events.map((one) => one.key).filter((key) => events.includes(key)),
      objects: parseObjects(objects),
    });
  };

  return (
    <DetailPage
      listLabel="Webhooks"
      onBack={onCancel}
      title={title}
      notices={messages}
      actions={
        <>
          <button type="button" className="button button-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" form="webhook-form" className="button" disabled={busy}>
            {submitLabel}
          </button>
        </>
      }
    >
      <form id="webhook-form" onSubmit={submit} noValidate autoComplete="off">
        {initial ? null : (
          <p className="hint" data-testid="webhook-acting">
            {catalogue.acting} This webhook will act as the member who is signed in: you.
          </p>
        )}
        <DetailSection title="Detail">
          <DetailGrid>
            <div className="field">
              <label htmlFor="webhook-name">Name</label>
              <input id="webhook-name" value={name} onChange={(event) => setName(event.target.value)} />
              <span className="hint">Lower case letters, digits and dashes.</span>
            </div>
            <div className="field">
              <label htmlFor="webhook-address">Address</label>
              <input
                id="webhook-address"
                type="url"
                inputMode="url"
                value={address}
                onChange={(event) => setAddress(event.target.value)}
              />
              <span className="hint">The https address of your own software that is told when records change.</span>
            </div>
          </DetailGrid>
        </DetailSection>
        <DetailSection title="What it is told" stack>
          {catalogue.events.map((one) => (
            <div className="field" key={one.key}>
              <label>
                <input type="checkbox" checked={events.includes(one.key)} onChange={(event) => toggle(one.key, event.target.checked)} />{" "}
                {one.title}
              </label>
              <span className="hint">{one.description}</span>
            </div>
          ))}
          <div className="field">
            <label htmlFor="webhook-objects">Only these objects</label>
            <textarea id="webhook-objects" value={objects} onChange={(event) => setObjects(event.target.value)} />
            <span className="hint">
              One API name per line. Leave it empty for every object its member may read.
              {catalogue.maxObjects ? ` At most ${catalogue.maxObjects}.` : ""}
            </span>
          </div>
        </DetailSection>
      </form>
    </DetailPage>
  );
}
