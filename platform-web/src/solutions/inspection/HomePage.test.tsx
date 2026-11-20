import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { rememberApplications } from "@/lib/applications/objectRoles";
import { setUrl } from "@/test/navigation";
import { fakeApi, json } from "@/test/fakeApi";
import { HomePage } from "./HomePage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
let named = "inspection";
vi.mock("./application", () => ({ useApplication: () => named, useApplicationLabel: () => "Home" }));

function application(apiName: string, options: Record<string, string>) {
  return { apiName, label: apiName, items: [], objectRoles: {}, options };
}

beforeEach(() => {
  fakeApi({
    "POST /api/v1/management-home/summary": () => json({ error: { code: "NOT_FOUND", message: "none", requestId: "r" } }, 404),
    "POST /api/v1/management-home/plant-board": () => json({ error: { code: "NOT_FOUND", message: "none", requestId: "r" } }, 404),
    "POST /api/v1/quality-home/summary": () => json({ error: { code: "NOT_FOUND", message: "none", requestId: "r" } }, 404),
    "GET /api/v1/approvals/inbox/summary": () => json({ data: { openTasks: 0, available: false } }),
  });
});

afterEach(() => {
  rememberApplications([]);
  vi.unstubAllGlobals();
});

describe("the Home page of an application of the kind", () => {
  it("draws the dashboard that compares the plants for an application whose file asks for it", async () => {
    rememberApplications([application("management-core", { dashboard: "management" })]);
    named = "management-core";
    setUrl("/apps/management-core/s-home");

    render(<HomePage />);

    // Option 1, the plant board, is the layout a person gets until they choose the other one (ADR-0266).
    expect(await screen.findByTestId("management-board")).toBeInTheDocument();
    expect(screen.queryByTestId("quality-home")).toBeNull();
  });

  it("keeps the dashboard it always had for every other application", async () => {
    rememberApplications([application("inspection", {}), application("document-management", { routedByNumber: "true" })]);
    for (const address of ["/apps/inspection/s-home", "/apps/document-management/s-home"]) {
      named = address.split("/")[2] ?? "inspection";
      setUrl(address);
      const { unmount } = render(<HomePage />);
      expect(await screen.findByTestId("quality-home")).toBeInTheDocument();
      expect(screen.queryByTestId("management-board")).toBeNull();
      expect(screen.queryByTestId("management-home")).toBeNull();
      unmount();
    }
  });

  it("keeps it too when the application is not in the list yet", async () => {
    rememberApplications([]);
    named = "inspection";
    setUrl("/apps/inspection/s-home");

    render(<HomePage />);

    expect(await screen.findByTestId("quality-home")).toBeInTheDocument();
  });
});
