"use client";

import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { ActionMessages, failureText } from "@/components/console/useAction";
import { DetailGrid, DetailItem, DetailPage, DetailSection, MissingDetail } from "@/components/ui/lists/DetailPage";
import { ListPage } from "@/components/ui/lists/ListPage";
import { Tag } from "@/components/ui/lists/Tag";
import { useListScreen } from "@/components/ui/lists/useListScreen";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { ChangeSetReport, itemText } from "./ChangeSetReport";
import { problemText, RELEASE_KIND_TEXT } from "./objectText";

type Release = components["schemas"]["ReleaseView"];
type Report = components["schemas"]["ChangeSetReportView"];

const kindText = (release: Release) =>
  `${RELEASE_KIND_TEXT[release.kind] ?? release.kind}${release.changeSetName ? ` “${release.changeSetName}”` : ""}`;

/**
 * The history of the organization's published metadata, as a list: every publication numbered, newest first. A row opens
 * the release: what it added, changed or removed, and, for the latest one, the check and the rollback. Only the latest
 * can be rolled back, and the API checks what the rollback would break (what depends on what, and records or values that
 * would be lost) and says so in its own words.
 */
export function ReleasesPanel() {
  const [releases, setReleases] = useState<Release[] | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  const [report, setReport] = useState<Report | undefined>();
  const [confirm, setConfirm] = useState(false);

  const reload = useCallback(async () => {
    try {
      const { data, error, response } = await api.GET("/api/v1/metadata/releases");
      if (!data) {
        setFailure(await failureText(error, response));
        return;
      }
      setFailure(undefined);
      setReleases(data.data);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  const screen = useListScreen(reload);
  const { busy, notice, problem, act } = screen;

  function check() {
    void act(async () => {
      const { data, error, response } = await api.POST("/api/v1/metadata/releases/latest/rollback-check");
      setReport(data?.data);
      return { error, response, text: "Here is what a rollback would do.", problem: problemText(error, response) };
    });
  }

  function rollBack() {
    setConfirm(false);
    void act(async () => {
      const { error, response } = await api.POST("/api/v1/metadata/releases/latest/rollback");
      if (response.ok) {
        setReport(undefined);
      }
      return { error, response, text: "The latest release was rolled back.", problem: problemText(error, response) };
    });
  }

  const messages = <ActionMessages notice={notice} problem={problem} testId="releases" />;
  const release = releases?.find((candidate) => String(candidate.number) === screen.openId);

  if (screen.openId && releases && !release) {
    return <MissingDetail listLabel="History" onBack={() => screen.back()} what="This release does not exist." />;
  }

  if (release) {
    return (
      <DetailPage
        listLabel="History"
        onBack={() => {
          setReport(undefined);
          setConfirm(false);
          screen.back();
        }}
        title={`Release ${release.number}`}
        notices={messages}
        testId="release"
        actions={
          release.latest ? (
            <>
              {confirm ? (
                <>
                  <button type="button" className="button button-danger" disabled={busy} onClick={rollBack}>
                    Yes, roll back release {release.number}
                  </button>
                  <button type="button" className="button button-secondary" onClick={() => setConfirm(false)}>
                    Cancel
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="button button-danger"
                  disabled={busy}
                  onClick={() => setConfirm(true)}
                >
                  Roll back this release
                </button>
              )}
              <button type="button" className="button" disabled={busy} onClick={check}>
                Check what a rollback would do
              </button>
            </>
          ) : undefined
        }
      >
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Release">{release.number}</DetailItem>
            <DetailItem label="Published">{new Date(release.createdAt).toLocaleString()}</DetailItem>
            <DetailItem label="What it was" wide>
              {kindText(release)}
              {release.undoesRelease != null ? ` (undoes release ${release.undoesRelease})` : ""}
              {release.rolledBackBy != null ? ` — rolled back by release ${release.rolledBackBy}` : ""}
            </DetailItem>
          </DetailGrid>
        </DetailSection>
        <DetailSection title="What it did">
          <ul data-testid="release-items">
            {release.items.map((item, index) => (
              <li key={index}>{itemText(item)}</li>
            ))}
          </ul>
        </DetailSection>
        {report ? (
          <DetailSection title="What a rollback would do">
            <ChangeSetReport report={report} testId="rollback-report" />
          </DetailSection>
        ) : null}
      </DetailPage>
    );
  }

  return (
    <ListPage<Release>
      title="History"
      items={releases}
      failure={failure}
      rowKey={(row) => String(row.number)}
      primaryHeader="Release"
      primary={(row) => `Release ${row.number}`}
      onOpen={(row) => screen.open(String(row.number))}
      columns={[
        { key: "kind", header: "What it was", kind: "muted", cell: kindText },
        { key: "published", header: "Published", kind: "muted", cell: (row) => new Date(row.createdAt).toLocaleString() },
        {
          key: "status",
          header: "Status",
          kind: "tag",
          cell: (row) => (
            <>
              {row.undoesRelease != null ? <Tag>Undoes release {row.undoesRelease}</Tag> : null}{" "}
              {row.rolledBackBy != null ? <Tag tone="error">Rolled back by release {row.rolledBackBy}</Tag> : null}{" "}
              {row.latest ? <Tag tone="on">Latest</Tag> : null}
            </>
          ),
        },
      ]}
      searchText={(row) => `Release ${row.number} ${kindText(row)}`}
      notices={messages}
      emptyText="Nothing has been published yet."
      testId="releases"
      rowTestId="release-row"
    />
  );
}
