import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { UsagePanel } from "./UsagePanel";

const USAGE = {
  enabled: true,
  organization: [
    { key: "k-one", title: "A limit of the organization", limit: 12, used: 2, remaining: 10, resetsAt: "2026-11-23T12:01:00Z", overridden: false },
    { key: "k-two", title: "Another limit", limit: 1000, used: 0, remaining: 1000, overridden: true },
  ],
  applications: [
    { name: "Reporting link", limits: [{ key: "k-one", title: "A limit of one application", limit: 5, used: 5, remaining: 0, overridden: false }] },
  ],
  words: "The words about what counts, in the API's words.",
};

afterEach(() => vi.unstubAllGlobals());

describe("UsagePanel", () => {
  it("shows the organization's limits and each application's, the numbers and the API's words", async () => {
    fakeApi({ "GET /api/v1/usage": () => json({ data: USAGE }) });
    render(<UsagePanel />);

    const organization = await screen.findByTestId("usage-organization");

    const limits = within(organization).getAllByTestId("usage-limit");
    expect(limits[0]).toHaveTextContent("A limit of the organization");
    expect(limits[0]).toHaveTextContent("2 of 12");
    expect(limits[0]).toHaveTextContent("Default");
    expect(limits[1]).toHaveTextContent("0 of 1000");
    expect(limits[1]).toHaveTextContent("Nothing counted yet");
    expect(limits[1]).toHaveTextContent("Set for this organization");
    const application = screen.getByTestId("usage-application");
    expect(application).toHaveTextContent("Reporting link");
    expect(application).toHaveTextContent("5 of 5");
    expect(screen.getByTestId("usage-words")).toHaveTextContent("The words about what counts, in the API's words.");
    expect(screen.queryByTestId("usage-off")).toBeNull();
  });

  it("says so when the platform has switched the limits off", async () => {
    fakeApi({ "GET /api/v1/usage": () => json({ data: { ...USAGE, enabled: false } }) });
    render(<UsagePanel />);

    expect(await screen.findByTestId("usage-off")).toHaveTextContent("switched off");
  });

  it("says there are no applications instead of drawing an empty table", async () => {
    fakeApi({ "GET /api/v1/usage": () => json({ data: { ...USAGE, applications: [] } }) });
    render(<UsagePanel />);

    expect(await screen.findByTestId("usage-no-applications")).toBeInTheDocument();
    expect(screen.queryByTestId("usage-application")).toBeNull();
  });

  it("shows the API's words when it refuses", async () => {
    fakeApi({ "GET /api/v1/usage": () => refusal("CONFLICT", "API usage is not part of this organization's plan.", 409) });
    render(<UsagePanel />);

    expect(await screen.findByRole("alert")).toHaveTextContent("not part of this organization's plan");
  });
});
