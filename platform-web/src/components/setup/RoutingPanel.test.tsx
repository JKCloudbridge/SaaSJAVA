import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { setUrl } from "@/test/navigation";
import { NumberingTabs } from "./NumberingTabs";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
const { offers } = vi.hoisted(() => ({ offers: vi.fn(() => true) }));
vi.mock("@/lib/session/SessionProvider", () => ({ useOffers: offers }));

const identifications = [
  { value: "F", label: "Format", active: true },
  { value: "WI", label: "Work instruction", active: true },
];
const applications = [{ application: "app-a", label: "Application A" }];
const rule = (extra = {}) => ({
  id: "r1", identification: "F", partA: "MC", application: "app-a", applicationLabel: "Application A",
  available: true, version: 0, ...extra,
});
const view = (rules: unknown[] = [], apps: unknown[] = applications) => ({ data: { rules, applications: apps, identifications } });
const numbering = () => json({ data: { series: [] } });

beforeEach(() => {
  offers.mockReturnValue(true);
  setUrl("/setup/numbering?section=routing");
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});
afterEach(() => vi.unstubAllGlobals());

describe("Application routing", () => {
  it("is a second tab of Numbering, drawn from what the API offers, with the rule's words", async () => {
    fakeApi({
      "GET /api/v1/numbering": numbering,
      "GET /api/v1/document-routing": () => json(view([rule(), rule({ id: "r2", identification: "WI", partA: "", version: 3 })])),
    });
    render(<NumberingTabs />);

    expect(await screen.findByRole("tab", { name: "Application routing" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("button", { name: "Format" })).toBeInTheDocument();
    expect(screen.getByText("MC")).toBeInTheDocument();
    expect(screen.getByText("Every Part A")).toBeInTheDocument();
    expect(screen.getAllByText("Application A")).toHaveLength(2);
  });

  it("draws no tab when the organization has nothing to route to and no rule", async () => {
    fakeApi({ "GET /api/v1/numbering": numbering, "GET /api/v1/document-routing": () => json(view([], [])) });
    render(<NumberingTabs />);

    await waitFor(() => expect(screen.queryByRole("tab")).not.toBeInTheDocument());
    expect(screen.queryByText("Application routing")).not.toBeInTheDocument();
  });

  it("draws no tab and no failure for a person the API refuses", async () => {
    fakeApi({
      "GET /api/v1/numbering": numbering,
      "GET /api/v1/document-routing": () => refusal("FORBIDDEN", "No.", 403),
    });
    render(<NumberingTabs />);

    await waitFor(() => expect(screen.queryByRole("tab")).not.toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("makes a rule from the identifications and applications the API gave and shows the saved list", async () => {
    const calls = fakeApi({
      "GET /api/v1/numbering": numbering,
      "GET /api/v1/document-routing": () => json(view()),
      "PUT /api/v1/document-routing": () => json(view([rule()])),
    });
    render(<NumberingTabs />);

    fireEvent.click(await screen.findByRole("button", { name: "New rule" }));
    fireEvent.change(await screen.findByLabelText("Identification"), { target: { value: "F" } });
    fireEvent.change(screen.getByLabelText("Part A"), { target: { value: "mc" } });
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));

    await waitFor(() => expect(calls.some((call) => call.key.startsWith("PUT "))).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.startsWith("PUT "))!.body)).toEqual({
      identification: "F", partA: "MC", application: "app-a",
    });
    expect(await screen.findByRole("status")).toHaveTextContent("Documents that already have their number are not moved.");
    expect(screen.getByText("Application A")).toBeInTheDocument();
  });

  it("shows the API's words for a refusal and keeps the typed rule", async () => {
    fakeApi({
      "GET /api/v1/numbering": numbering,
      "GET /api/v1/document-routing": () => json(view()),
      "PUT /api/v1/document-routing": () => refusal("CONFLICT", "A rule for this combination already exists. Change that one instead.", 409),
    });
    render(<NumberingTabs />);

    fireEvent.click(await screen.findByRole("button", { name: "New rule" }));
    fireEvent.change(await screen.findByLabelText("Identification"), { target: { value: "F" } });
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("A rule for this combination already exists.");
    expect(screen.getByLabelText("Identification")).toHaveValue("F");
  });

  it("changes a rule at the version it read and removes it at that version", async () => {
    let removedAt: string | null = null;
    const calls = fakeApi({
      "GET /api/v1/numbering": numbering,
      "GET /api/v1/document-routing": () => json(view([rule({ version: 4 })])),
      "PUT /api/v1/document-routing": () => json(view([rule({ version: 5 })])),
      "DELETE /api/v1/document-routing/r1": (request) => {
        removedAt = new URL(request.url).searchParams.get("version");
        return json(view([]));
      },
    });
    render(<NumberingTabs />);

    fireEvent.click(await screen.findByRole("button", { name: "Format" }));
    fireEvent.click(await screen.findByRole("button", { name: "Save rule" }));
    await waitFor(() => expect(calls.some((call) => call.key.startsWith("PUT "))).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.startsWith("PUT "))!.body)).toMatchObject({ version: 4 });

    fireEvent.click(await screen.findByRole("button", { name: "Format" }));
    fireEvent.click(await screen.findByRole("button", { name: "Remove rule" }));
    await waitFor(() => expect(calls.some((call) => call.key.startsWith("DELETE "))).toBe(true));
    expect(removedAt).toBe("5");
  });

  it("says a rule does nothing while the organization lacks its application, and still lets it be removed", async () => {
    fakeApi({
      "GET /api/v1/numbering": numbering,
      "GET /api/v1/document-routing": () => json(view([rule({ available: false, applicationLabel: null })], [])),
      "DELETE /api/v1/document-routing/r1": () => json(view([], [])),
    });
    render(<NumberingTabs />);

    expect(await screen.findByText("Not available in this organization")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New rule" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Format" }));
    expect(await screen.findByText(/does nothing until it is given again/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove rule" })).toBeEnabled();
  });
});
