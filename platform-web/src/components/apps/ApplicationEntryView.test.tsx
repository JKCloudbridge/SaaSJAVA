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

  it("waits for the list before saying anything", () => {
    shellApi();
    renderEntry();

    expect(screen.getByTestId("entry-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("entry-unavailable")).toBeNull();
  });

  it("says a page is being prepared for an object, until the record screens exist", async () => {
    params = { application: "Field__c", entry: "o-Deal__c" };
    shellApi();
    renderEntry();

    const note = await screen.findByTestId("entry-not-built");

    expect(note).toHaveTextContent("This page is being prepared.");
    expect(note).toHaveTextContent("list of records of Field Service");
    expect(screen.getByRole("heading", { level: 1, name: "Deals" })).toBeInTheDocument();
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
