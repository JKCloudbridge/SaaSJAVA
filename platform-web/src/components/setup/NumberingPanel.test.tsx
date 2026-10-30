import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { NumberingPanel } from "./NumberingPanel";

const { offers } = vi.hoisted(() => ({ offers: vi.fn(() => true) }));
vi.mock("@/lib/session/SessionProvider", () => ({ useOffers: offers }));

const format = "WI/{field:partA}/{seq:2}";
const series = {
  key: "document", objectApiName: "InspectionTemplate", label: "Template", selector: "category", optionalField: "partA",
  fields: [
    { apiName: "category", label: "Identification", values: [{ value: "WI", label: "WI", active: true }] },
    { apiName: "partA", label: "Part A", values: [{ value: "PRD", label: "Production", active: true }] },
  ],
  settings: [{ kind: "CATEGORY", selectorValue: "WI", label: "Work instruction", active: true, format, source: "SHIPPED", version: 0, width: 2 }],
};
const preview = (ready: boolean, next = 1) => ({
  data: { example: ready ? `WI/PRD/${String(next).padStart(2, "0")}` : null, nextValue: ready ? next : null, width: 2,
    fields: ["partA"], missingFields: ready ? [] : ["partA"], message: ready ? "An example, not a reservation." : "Choose the parts." },
});

beforeEach(() => {
  offers.mockReturnValue(true);
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});
afterEach(() => vi.unstubAllGlobals());

function setup(saveResponse = () => json({ data: { ...series.settings[0], source: "ORGANIZATION", version: 1 } })) {
  return fakeApi({
    "GET /api/v1/numbering": () => json({ data: { series: [series] } }),
    "POST /api/v1/numbering/document/preview": async (request) => {
      const body = await request.json();
      return json(preview(Boolean(body.values.partA)));
    },
    "PUT /api/v1/numbering/document/formats": saveResponse,
    "POST /api/v1/numbering/document/counter": () => json(preview(true, 37)),
    "POST /api/v1/numbering/document/formats/reset": () => json({ data: { series: [series] } }),
    "GET /api/v1/numbering/document/counters": () => json({ data: { counters: [], nextCursor: null } }),
  });
}

async function openAndPreview() {
  fireEvent.click(await screen.findByRole("button", { name: "Work instruction" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview" }));
  fireEvent.change(await screen.findByLabelText("Part A"), { target: { value: "PRD" } });
  fireEvent.click(screen.getByRole("button", { name: "Preview" }));
  await screen.findByText("WI/PRD/01");
}

describe("NumberingPanel", () => {
  it("lists the server's settings and requires a fresh preview before saving a changed format", async () => {
    const calls = setup();
    render(<NumberingPanel />);
    await openAndPreview();
    expect(screen.getByRole("button", { name: "Save format" })).toBeEnabled();
    fireEvent.change(screen.getByLabelText("Number format"), { target: { value: "WI/{field:partA}/{seq:3}" } });
    expect(screen.getByRole("button", { name: "Save format" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    fireEvent.change(await screen.findByLabelText("Part A"), { target: { value: "PRD" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Save format" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Save format" }));
    await waitFor(() => expect(calls.some((call) => call.key.startsWith("PUT "))).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.startsWith("PUT "))!.body)).toEqual({
      kind: "CATEGORY", selectorValue: "WI", format: "WI/{field:partA}/{seq:3}", version: 0,
    });
  });

  it("lists the combinations that already have numbers, read only, a page at a time (FA11B)", async () => {
    const pages: Record<string, unknown> = {
      "": { counters: [{ combination: "WI/PRD", nextValue: 6, lastIssued: 5 }], nextCursor: "50" },
      "50": { counters: [{ combination: "WI/QLT", nextValue: 2, lastIssued: 1 }], nextCursor: null },
    };
    const calls = fakeApi({
      "GET /api/v1/numbering": () => json({ data: { series: [series] } }),
      "GET /api/v1/numbering/document/counters": (request) =>
        json({ data: pages[new URL(request.url).searchParams.get("cursor") ?? ""] }),
    });
    render(<NumberingPanel />);

    expect(await screen.findByText("WI/PRD")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Combinations that already have numbers/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show more" }));
    expect(await screen.findByText("WI/QLT")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show more" })).not.toBeInTheDocument();
    expect(calls.filter((call) => call.key.startsWith("GET /api/v1/numbering/document/counters"))).toHaveLength(2);
  });

  it("shows the server's refusal without claiming a save succeeded", async () => {
    setup(() => refusal("CONCURRENT_MODIFICATION", "Someone changed this setting. Reload it.", 409));
    render(<NumberingPanel />);
    await openAndPreview();
    fireEvent.click(screen.getByRole("button", { name: "Save format" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Someone changed this setting");
  });

  it("sends the actual pair and requested next count through the dedicated advance endpoint", async () => {
    const calls = setup();
    render(<NumberingPanel />);
    await openAndPreview();
    fireEvent.change(screen.getByLabelText("Next count"), { target: { value: "37" } });
    fireEvent.click(screen.getByRole("button", { name: "Raise next count" }));
    await screen.findByText("WI/PRD/37");
    const sent = calls.find((call) => call.key.endsWith("/counter"));
    expect(JSON.parse(sent!.body)).toEqual({ values: { category: "WI", partA: "PRD" }, nextValue: 37 });
    expect(calls.filter((call) => call.key.endsWith("/formats"))).toHaveLength(0);
  });

  it("goes back to the platform's format with the version read, only for an organization's own format", async () => {
    const own = { ...series, settings: [{ ...series.settings[0], source: "ORGANIZATION", version: 3 }] };
    const calls = fakeApi({
      "GET /api/v1/numbering": () => json({ data: { series: [own] } }),
      "POST /api/v1/numbering/document/formats/reset": () => json({ data: { series: [series] } }),
    });
    render(<NumberingPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Work instruction" }));
    fireEvent.click(screen.getByRole("button", { name: "Use the platform's format" }));
    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/formats/reset"))).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.endsWith("/formats/reset"))!.body)).toEqual({
      kind: "CATEGORY", selectorValue: "WI", version: 3,
    });
  });

  it("does not offer the reset for a format that comes with the platform", async () => {
    setup();
    render(<NumberingPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Work instruction" }));
    expect(screen.queryByRole("button", { name: "Use the platform's format" })).not.toBeInTheDocument();
  });

  it("offers no management controls or settings request when the session lacks the ability", () => {
    offers.mockReturnValue(false);
    const calls = setup();
    render(<NumberingPanel />);
    expect(screen.getByRole("status")).toHaveTextContent("do not have access");
    expect(screen.queryByRole("button", { name: "Save format" })).not.toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
