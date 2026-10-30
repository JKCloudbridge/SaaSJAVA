"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, type ActionResult } from "@/components/console/useAction";
import { Modal } from "@/components/ui/Modal";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import { failureFromResponse } from "@/lib/api/errors";
import { lastUsedWords, usedByWords, type Credential } from "@/lib/credentials/model";
import { useOffers } from "@/lib/session/SessionProvider";

/** The API's words for a refusal, with every problem it lists. */
async function refusal(error: unknown, response: Response): Promise<string> {
  if (response.status === 429) {
    return failureText(error, response);
  }
  const failure = failureFromResponse(error, response);
  const lines = Object.values(failure.fields ?? {}).flat();
  return lines.length > 0 ? `${failure.message} ${lines.join(" ")}` : failure.message;
}

/**
 * The organization's credentials (Sprint 27, ADR-0212, ADR-0213). A list of names with their version and dates; a row
 * opens the credential; New and Replace open a form with one write-only field.
 *
 * The value is typed into an input the page does not control: it is read once, when the form is sent, put into the
 * request and wiped from the field at that moment, so it is never in the page's state, never shown again and never
 * kept after the request is made. There is no response field that could hold it. The page holds no limit, no reason
 * and no list of its own and decides nothing: it shows what the API answers, in its words.
 */
export function CredentialsPanel() {
  const [items, setItems] = useState<Credential[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [replacing, setReplacing] = useState(false);
  const [removing, setRemoving] = useState<Credential | undefined>();
  const offersManage = useOffers("credentials.manage");

  const reload = useCallback(async () => {
    try {
      const list = await api.GET("/api/v1/credentials");
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setItems(list.data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;
  const messages = <ActionMessages notice={notice} problem={problem} testId="credentials" />;

  const save = (name: string, value: string, existing: boolean): Promise<void> =>
    act(async (): Promise<ActionResult> => {
      const result = await api.PUT("/api/v1/credentials/{name}", { params: { path: { name } }, body: { value } });
      if (result.response.ok) {
        if (existing) {
          setReplacing(false);
        } else {
          screen.back(true);
        }
      }
      return {
        error: result.error,
        response: result.response,
        text: existing
          ? "The credential has a new version. Connections that use it must be tested again."
          : "The credential was saved. It cannot be shown again.",
        problem: result.response.ok ? undefined : await refusal(result.error, result.response),
      };
    });

  const goBack = (item: Credential) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.POST("/api/v1/credentials/{name}/rollback", {
        params: { path: { name: item.name } },
      });
      return { error, response, text: "The version before is the current one again." };
    });

  const remove = (item: Credential) =>
    act(async (): Promise<ActionResult> => {
      const { error, response } = await api.DELETE("/api/v1/credentials/{name}", { params: { path: { name: item.name } } });
      setRemoving(undefined);
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The credential was removed." };
    });

  if (screen.creating && items) {
    return (
      <CredentialForm
        key="new"
        title="New credential"
        submitLabel="Save the credential"
        busy={busy}
        messages={messages}
        onCancel={() => screen.back()}
        onSubmit={(name, value) => void save(name, value, false)}
      />
    );
  }

  const item = items?.find((candidate) => candidate.name === screen.openId);
  if (screen.openId && items && !item) {
    return <MissingDetail listLabel="Credentials" onBack={() => screen.back()} what="This credential does not exist any more." />;
  }

  if (item && replacing) {
    return (
      <CredentialForm
        key={`${item.name}-${item.version}`}
        title={`Replace ${item.name}`}
        submitLabel="Save a new version"
        existing={item.name}
        busy={busy}
        messages={messages}
        onCancel={() => setReplacing(false)}
        onSubmit={(name, value) => void save(name, value, true)}
      />
    );
  }

  if (item) {
    return (
      <>
        <DetailPage
          listLabel="Credentials"
          onBack={() => screen.back()}
          title={item.name}
          notices={messages}
          testId="credential"
          actions={
            offersManage ? (
              <>
                <button type="button" className="button button-secondary" disabled={busy} onClick={() => setReplacing(true)}>
                  Replace
                </button>
                {item.canRollBack ? (
                  <button type="button" className="button button-secondary" disabled={busy} onClick={() => void goBack(item)}>
                    Go back one version
                  </button>
                ) : null}
                <button type="button" className="button button-danger" disabled={busy} onClick={() => setRemoving(item)}>
                  Remove
                </button>
              </>
            ) : null
          }
        >
          <DetailSection title="Detail">
            <DetailGrid>
              <DetailItem label="Version">{String(item.version)}</DetailItem>
              <DetailItem label="Created">{new Date(item.createdAt).toLocaleString()}</DetailItem>
              <DetailItem label="Changed">{new Date(item.updatedAt).toLocaleString()}</DetailItem>
              <DetailItem label="Last used" tone={item.lastUsedAt ? undefined : "muted"}>
                {lastUsedWords(item)}
              </DetailItem>
              <DetailItem label="Used by">{usedByWords(item)}</DetailItem>
            </DetailGrid>
          </DetailSection>
          <DetailSection title="The value">
            <p className="hint">The value is kept sealed and is never shown to anyone. To change it, replace it.</p>
          </DetailSection>
        </DetailPage>
        {removing ? (
          <Modal title={`Remove ${removing.name}?`} onClose={() => setRemoving(undefined)} testId="credential-remove">
            <p>Every version of this credential is deleted and cannot be brought back.</p>
            <div className="form-actions">
              <button type="button" className="button button-secondary" onClick={() => setRemoving(undefined)}>
                Cancel
              </button>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => void remove(removing)}>
                Remove the credential
              </button>
            </div>
          </Modal>
        ) : null}
      </>
    );
  }

  return (
    <ListPage<Credential>
      title="Credentials"
      items={items}
      failure={failure}
      rowKey={(row) => row.name}
      primaryHeader="Name"
      primary={(row) => row.name}
      sortPrimary
      columns={[
        { key: "version", header: "Version", kind: "muted", cell: (row) => String(row.version) },
        { key: "changed", header: "Changed", kind: "muted", cell: (row) => new Date(row.updatedAt).toLocaleDateString() },
        { key: "used", header: "Last used", kind: "muted", cell: (row) => lastUsedWords(row) },
        { key: "by", header: "Used by", kind: "muted", cell: (row) => usedByWords(row) },
      ]}
      onOpen={(row) => {
        setReplacing(false);
        screen.open(row.name);
      }}
      searchText={(row) => row.name}
      onNew={offersManage ? screen.create : undefined}
      newLabel="New credential"
      notices={messages}
      emptyText="There are no credentials yet."
      testId="credentials"
      rowTestId="credential-row"
    />
  );
}

interface FormProps {
  title: string;
  submitLabel: string;
  /** The name of the credential being replaced; without it the form asks for a name. */
  existing?: string;
  busy: boolean;
  messages: ReactNode;
  onCancel: () => void;
  onSubmit: (name: string, value: string) => void;
}

/** The form: a name (for a new credential) and one write-only field for the value. */
function CredentialForm({ title, submitLabel, existing, busy, messages, onCancel, onSubmit }: FormProps) {
  const [name, setName] = useState("");
  const value = useRef<HTMLInputElement>(null);
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const field = value.current;
    const typed = field?.value ?? "";
    if (field) {
      // Wiped at once: from here on the value exists only in the request that is about to be made.
      field.value = "";
    }
    onSubmit(existing ?? name, typed);
  };
  return (
    <DetailPage
      listLabel="Credentials"
      onBack={onCancel}
      title={title}
      notices={messages}
      actions={
        <>
          <button type="button" className="button button-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" form="credential-form" className="button" disabled={busy}>
            {submitLabel}
          </button>
        </>
      }
    >
      <form id="credential-form" onSubmit={submit} noValidate autoComplete="off">
        <DetailSection title="Detail">
          <DetailGrid>
            {existing ? null : (
              <div className="field">
                <label htmlFor="credential-name">Name</label>
                <input id="credential-name" value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} />
                <span className="hint">Connections refer to a credential by this name. It cannot be changed later.</span>
              </div>
            )}
            <div className="field">
              <label htmlFor="credential-value">Value</label>
              <input id="credential-value" ref={value} type="password" autoComplete="new-password" spellCheck={false} />
              <span className="hint">It is sealed when you save and is never shown again.</span>
            </div>
          </DetailGrid>
        </DetailSection>
      </form>
    </DetailPage>
  );
}
