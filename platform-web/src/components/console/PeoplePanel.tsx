"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ActionMessages, failureText } from "./useAction";

type Person = components["schemas"]["PlatformPersonView"];

const ROLES = ["PLATFORM_ADMIN", "PLATFORM_SUPPORT", "PLATFORM_BILLING"] as const;

type Loaded = { kind: "loading" } | { kind: "ok"; items: Person[] } | { kind: "failed"; message: string };

/**
 * Who holds a platform role, as a list; a row opens the person (and takes the role away), New gives a role. Platform
 * administrators only: anybody else gets the API's refusal, shown in its words. The last platform administrator cannot be
 * removed; the API says so and the page repeats it.
 */
export function PeoplePanel() {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<string>(ROLES[1]);

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/platform/people");
      setLoaded(
        data
          ? { kind: "ok", items: data.data }
          : { kind: "failed", message: await failureText(error, response) },
      );
    } catch {
      setLoaded({ kind: "failed", message: COMMON_TEXT.network });
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  function grant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/platform/people", { body: { email, role } });
      if (data) {
        setEmail("");
        screen.back(true);
      }
      return { error, response, text: "The role was granted." };
    });
  }

  const revoke = (assignmentId: string) =>
    act(async () => {
      const { error, response } = await api.DELETE("/api/v1/platform/people/{assignmentId}", {
        params: { path: { assignmentId } },
      });
      if (response.ok) {
        screen.back(true);
      }
      return { error, response, text: "The role was taken away." };
    });

  const messages = <ActionMessages notice={notice} problem={problem} testId="people" />;
  const items = loaded.kind === "ok" ? loaded.items : undefined;

  if (screen.creating && items) {
    const cancel = () => screen.back();
    return (
      <DetailPage
        listLabel="Platform people"
        onBack={cancel}
        title="Give a platform role"
        intro="The person must already have an account."
        notices={messages}
        actions={
          <>
            <button type="button" className="button button-secondary" onClick={cancel}>
              Cancel
            </button>
            <button type="submit" form="people-form" className="button" disabled={busy}>
              Give the role
            </button>
          </>
        }
      >
        <form id="people-form" onSubmit={grant} noValidate aria-label="Give a platform role">
          <DetailSection title="Detail">
            <DetailGrid>
              <div className="field">
                <label htmlFor="people-email">E-mail address</label>
                <input
                  id="people-email"
                  type="email"
                  autoComplete="off"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label htmlFor="people-role">Role</label>
                <select id="people-role" value={role} onChange={(event) => setRole(event.target.value)}>
                  {ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
              </div>
            </DetailGrid>
          </DetailSection>
        </form>
      </DetailPage>
    );
  }

  const person = items?.find((candidate) => candidate.id === screen.openId);
  if (screen.openId && items && !person) {
    return <MissingDetail listLabel="Platform people" onBack={() => screen.back()} what="This role does not exist any more." />;
  }

  if (person) {
    return (
      <DetailPage
        listLabel="Platform people"
        onBack={() => screen.back()}
        title={person.displayName}
        notices={messages}
        testId="person-detail"
        actions={
          <button type="button" className="button button-danger" disabled={busy} onClick={() => void revoke(person.id)}>
            Take away
          </button>
        }
      >
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Name">{person.displayName}</DetailItem>
            <DetailItem label="E-mail address">{person.email}</DetailItem>
            <DetailItem label="Role" tone="mono">
              {person.role}
            </DetailItem>
            <DetailItem label="Since">{new Date(person.since).toLocaleDateString()}</DetailItem>
          </DetailGrid>
        </DetailSection>
      </DetailPage>
    );
  }

  return (
    <ListPage<Person>
      title="Platform people"
      items={items}
      failure={loaded.kind === "failed" ? loaded.message : undefined}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.displayName}
      primaryExtra={(row) => row.email}
      onOpen={(row) => screen.open(row.id)}
      columns={[
        { key: "role", header: "Role", kind: "tag", cell: (row) => <Tag tone="on">{row.role}</Tag> },
      ]}
      searchText={(row) => `${row.displayName} ${row.email} ${row.role}`}
      onNew={screen.create}
      newLabel="Give a platform role"
      notices={messages}
      emptyText="Nobody holds a platform role."
      testId="people"
      rowTestId="person-row"
    />
  );
}
