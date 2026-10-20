"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText, useAction } from "@/components/console/useAction";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { useOffers } from "@/lib/session/SessionProvider";
import { problemText } from "./objectText";
import { addToSet, DRAFTED_TEXT, useWorkingSet } from "./workingSet";

type ApplicationView = components["schemas"]["ApplicationView"];
type ProfileView = components["schemas"]["ProfileView"];

const hrefOf = (application: ApplicationView) => `/setup/applications/${encodeURIComponent(application.apiName)}`;

/**
 * The applications of the organization: the list, and the way into the builder where an application is made and changed
 * (its settings and its navigation, built by dragging). A row opens the builder; New opens it empty. Removing is done here,
 * live or, when a change set is chosen, as a draft. The page shows what the API answers.
 */
export function ApplicationsPanel() {
  const { id: workingSet } = useWorkingSet();
  const [applications, setApplications] = useState<ApplicationView[] | undefined>();
  const [profiles, setProfiles] = useState<ProfileView[]>([]);
  const [failure, setFailure] = useState<string | undefined>();
  const offersManage = useOffers("metadata.manage");

  const reload = useCallback(async () => {
    try {
      const [list, profileList] = await Promise.all([
        api.GET("/api/v1/metadata/applications"),
        api.GET("/api/v1/profiles"),
      ]);
      if (!list.data) {
        setFailure(await failureText(list.error, list.response));
        return;
      }
      setFailure(undefined);
      setApplications(list.data.data);
      setProfiles(profileList.data?.data ?? []);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const { busy, notice, problem, act } = useAction(reload);

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

  const profileName = (id: string) => profiles.find((profile) => profile.id === id)?.name ?? "A profile that was deleted";

  return (
    <ListPage<ApplicationView>
      title="Applications"
      intro="An application is a menu of the organization: the objects, tabs, record pages and links its members see. Members see an application when it names their profile, or when it names no profile."
      items={applications}
      failure={failure}
      rowKey={(row) => row.apiName}
      primaryHeader="Application"
      primary={(row) => row.label}
      primaryExtra={(row) => row.description || null}
      openHref={hrefOf}
      columns={[
        { key: "pages", header: "Pages", kind: "muted", cell: (row) => row.definition.items.length },
        { key: "api", header: "API name", kind: "muted", cell: (row) => <code>{row.apiName}</code> },
        {
          key: "seen",
          header: "Seen by",
          kind: "tag",
          cell: (row) => {
            const named = row.definition.profiles ?? [];
            return named.length === 0 ? <Tag tone="on">Every member</Tag> : <Tag>{named.map(profileName).join(", ")}</Tag>;
          },
        },
        {
          key: "actions",
          header: "Actions",
          kind: "actions",
          cell: (row) => (
            <button type="button" className="link-button" disabled={busy} onClick={() => remove(row)}>
              Remove
            </button>
          ),
        },
      ]}
      searchText={(row) => `${row.label} ${row.apiName} ${row.description ?? ""}`}
      newHref={offersManage ? "/setup/applications/new" : undefined}
      newLabel="New application"
      notices={<ActionMessages notice={notice} problem={problem} testId="applications" />}
      emptyText="No applications yet."
      testId="applications"
      rowTestId="application-row"
    />
  );
}
