import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json } from "@/test/fakeApi";
import { FIELD, shellApi, ShellProviders } from "@/test/shellApi";
import { ApplicationEntryView } from "./ApplicationEntryView";

let params: { application: string; entry: string };
vi.mock("next/navigation", () => ({
  useParams: () => params,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/",
}));

interface ListProps {
  objectApiName: string;
  newHref?: string;
  openHref: (id: string) => string;
  recordHref: (objectApiName: string, id: string) => string;
  backHref: string;
}
const lists: ListProps[] = [];
vi.mock("@/components/records/ObjectListPage", () => ({
  ObjectListPage: (props: ListProps) => {
    lists.push(props);
    return <div data-testid="object-list">{props.objectApiName}</div>;
  },
}));

const frames: { customPage: string; label: string; assetId?: string }[] = [];
vi.mock("./CustomPageFrame", () => ({
  CustomPageFrame: (props: { customPage: string; label: string; assetId?: string }) => {
    frames.push(props);
    return <div data-testid="frame">{props.customPage}</div>;
  },
}));

beforeEach(() => {
  params = { application: "Field__c", entry: "c-Inspections__c" };
  frames.length = 0;
  lists.length = 0;
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderEntry() {
  return render(
    <ShellProviders>
      <ApplicationEntryView />
    </ShellProviders>,
  );
}

describe("a page of an application", () => {
  it("shows a custom page in the frame, with the asset the list names so a copy already held is used", async () => {
    shellApi();
    renderEntry();

    expect(await screen.findByTestId("frame")).toHaveTextContent("Inspection__c");

    expect(screen.getByRole("heading", { level: 1, name: "Inspections" })).toBeInTheDocument();
    expect(frames[0]).toMatchObject({
      customPage: "Inspection__c",
      label: "Inspections",
      assetId: "11111111-2222-3333-4444-555555555555",
    });
  });

  it("draws the page a shipped application registered for the key, under the entry's title", async () => {
    params = { application: "inspection", entry: "s-templates" };
    shellApi({
      applications: [
        {
          apiName: "inspection",
          label: "Audit maintenance",
          items: [{ type: "PLATFORM", label: "Templates", pageKey: "templates", available: true }],
        },
      ],
    });
    renderEntry();

    expect(await screen.findByTestId("solution-page-placeholder")).toHaveTextContent("nothing here yet");
    expect(screen.getByRole("heading", { level: 1, name: "Templates" })).toBeInTheDocument();
  });

  it("says a shipped page is not available when this release has no page for its key", async () => {
    params = { application: "inspection", entry: "s-elsewhere" };
    shellApi({
      applications: [
        {
          apiName: "inspection",
          label: "Audit maintenance",
          items: [{ type: "PLATFORM", label: "Elsewhere", pageKey: "elsewhere", available: true }],
        },
      ],
    });
    renderEntry();

    expect(await screen.findByTestId("entry-unavailable")).toHaveTextContent("This page is not available.");
    expect(screen.queryByTestId("solution-page-placeholder")).toBeNull();
  });

  it("waits for the list before saying anything", () => {
    shellApi();
    renderEntry();

    expect(screen.getByTestId("entry-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("entry-unavailable")).toBeNull();
  });

  it("shows the list of an object, and every screen of its records opens inside the application", async () => {
    params = { application: "Field__c", entry: "o-Deal__c" };
    shellApi();
    renderEntry();

    expect(await screen.findByTestId("object-list")).toHaveTextContent("Deal__c");

    const props = lists[0]!;
    expect(props.newHref).toBe("/apps/Field__c/o-Deal__c/new");
    expect(props.openHref("r 1")).toBe("/apps/Field__c/o-Deal__c/records/r%201");
    // A link to a record of another object leaves for that object's own address.
    expect(props.recordHref("Account__c", "a1")).toBe("/records/Account__c/a1");
    expect(props.backHref).toBe("/");
    expect(screen.queryByTestId("entry-not-built")).toBeNull();
  });

  it("still says a record page is being prepared, until Sprint 20", async () => {
    params = { application: "Field__c", entry: "p-Deal__c.Overview__c" };
    shellApi({
      applications: [
        { ...FIELD, items: [{ type: "RECORD_PAGE", label: "Overview", objectApiName: "Deal__c", pageApiName: "Overview__c", available: true }] },
      ],
    });
    renderEntry();

    const note = await screen.findByTestId("entry-not-built");

    expect(note).toHaveTextContent("This page is being prepared.");
    expect(note).toHaveTextContent("record page of Field Service");
  });

  it("asks the API once more before saying a page is not available, in case the list is old", async () => {
    params = { application: "Field__c", entry: "c-Brand-new__c" };
    let calls = 0;
    shellApi({
      applicationsAnswer: () => {
        calls += 1;
        return json({ data: [FIELD] });
      },
    });
    renderEntry();

    expect(await screen.findByTestId("entry-unavailable")).toHaveTextContent("This page is not available.");
    expect(calls).toBe(2);
  });

  it("finds a page that the second look turns up", async () => {
    params = { application: "Field__c", entry: "c-Inspections__c" };
    let calls = 0;
    shellApi({ applicationsAnswer: () => json({ data: calls++ === 0 ? [] : [FIELD] }) });
    renderEntry();

    expect(await screen.findByTestId("frame")).toBeInTheDocument();
    expect(screen.queryByTestId("entry-unavailable")).toBeNull();
  });

  it("says an application is not available exactly as it says a page is not", async () => {
    params = { application: "Gone__c", entry: "o-Deal__c" };
    shellApi();
    renderEntry();

    expect(await screen.findByTestId("entry-unavailable")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the start" })).toHaveAttribute("href", "/");
  });

  it("shows a custom page that cannot be opened now as such, without a frame", async () => {
    shellApi({
      applications: [{ ...FIELD, items: [{ ...FIELD.items[1]!, available: false }] }],
    });
    renderEntry();

    await waitFor(() =>
      expect(screen.getByText("This page cannot be opened right now. Try again in a few minutes.")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("frame")).toBeNull();
  });

  it("reads the names in the address as the API wrote them, not as a browser escaped them", async () => {
    params = { application: encodeURIComponent("Field__c"), entry: encodeURIComponent("c-Inspections__c") };
    shellApi();
    renderEntry();

    expect(await screen.findByTestId("frame")).toBeInTheDocument();
  });
});
