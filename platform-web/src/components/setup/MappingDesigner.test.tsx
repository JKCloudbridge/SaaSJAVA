import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { dragAndDrop } from "@/test/dragDrop";
import { fakeApi, json, refusal, type Handler } from "@/test/fakeApi";
import { CONNECTION } from "@/test/integrations";
import { MappingDesigner } from "./MappingDesigner";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const CATALOGUE = {
  data: {
    conflicts: [{ key: "rule-1", label: "Rule one", hint: "" }],
    directions: [
      { key: "dir-out", label: "Out", hint: "" },
      { key: "dir-in", label: "In", hint: "" },
    ],
    externalTypes: [{ key: "type-1", label: "Type one", hint: "" }],
    conversions: [
      { key: "conv-1", label: "Conversion one", hint: "" },
      { key: "conv-2", label: "Conversion two", hint: "", input: "factor" },
      { key: "conv-3", label: "Conversion three", hint: "", input: "pairs" },
    ],
  },
};

const OBJECT = {
  data: {
    apiName: "Order__c",
    label: "Order",
    fields: [
      { apiName: "total__c", label: "Total", type: "T", retired: false },
      { apiName: "state__c", label: "State", type: "T", retired: false },
    ],
  },
};

const CONNECTED = { ...CONNECTION, scope: [{ object: "Order__c", read: true, create: true, update: true }] };

function api(extra: Record<string, Handler> = {}) {
  return fakeApi({
    "GET /api/v1/integrations/mapping-catalogue": () => json(CATALOGUE),
    "GET /api/v1/integrations/link-a/mappings": () => json({ data: [] }),
    "GET /api/v1/integrations/link-a/deliveries": () => json({ data: [] }),
    "GET /api/v1/metadata/objects/Order__c": () => json(OBJECT),
    ...extra,
  });
}

async function designer() {
  render(<MappingDesigner connection={CONNECTED} />);
  return screen.findByTestId("mapping-designer");
}

describe("MappingDesigner", () => {
  it("draws its choices from the API's catalogue and the fields from the object", async () => {
    api();
    const page = await designer();

    expect(within(page).getByText("Rule one")).toBeInTheDocument();
    expect(await within(page).findByRole("button", { name: "Total" })).toBeInTheDocument();
    expect(within(page).getByRole("button", { name: "State" })).toBeInTheDocument();
  });

  it("adds a field from the palette with Enter and by dropping it, which are the same change", async () => {
    api();
    const page = await designer();
    const chip = await within(page).findByRole("button", { name: "Total" });

    fireEvent.keyDown(chip, { key: "Enter" });
    expect(within(page).getAllByTestId("mapping-field")).toHaveLength(1);

    dragAndDrop(await within(page).findByRole("button", { name: "State" }), screen.getByTestId("mapping-canvas"));

    expect(within(page).getAllByTestId("mapping-field")).toHaveLength(2);
    expect(within(page).queryByRole("button", { name: "Total" })).not.toBeInTheDocument();
  });

  it("shows the extra input the catalogue says a conversion needs, and no other", async () => {
    api();
    const page = await designer();
    fireEvent.keyDown(await within(page).findByRole("button", { name: "Total" }), { key: "Enter" });
    const conversion = within(page).getByLabelText("Conversion of total__c");

    expect(within(page).queryByLabelText("Factor of total__c")).not.toBeInTheDocument();
    fireEvent.change(conversion, { target: { value: "conv-2" } });
    expect(within(page).getByLabelText("Factor of total__c")).toBeInTheDocument();
    fireEvent.change(conversion, { target: { value: "conv-3" } });
    expect(within(page).getByRole("button", { name: "Add a pair" })).toBeInTheDocument();
    expect(within(page).queryByLabelText("Factor of total__c")).not.toBeInTheDocument();
  });

  it("moves and takes out a field with its buttons, named after the field", async () => {
    api();
    const page = await designer();
    fireEvent.keyDown(await within(page).findByRole("button", { name: "Total" }), { key: "Enter" });
    fireEvent.keyDown(await within(page).findByRole("button", { name: "State" }), { key: "Enter" });

    fireEvent.click(within(page).getByRole("button", { name: "Move total__c down" }));
    expect(within(page).getAllByTestId("mapping-field")[0]).toHaveTextContent("State");

    fireEvent.click(within(page).getByRole("button", { name: "Take out state__c" }));
    expect(within(page).getAllByTestId("mapping-field")).toHaveLength(1);
  });

  it("saves the draft in the API's shape and shows the API's words for a refusal", async () => {
    const calls = api({
      "PUT /api/v1/integrations/link-a/mappings/Order__c": () =>
        refusal("VALIDATION_ERROR", "The definition is not valid.", 400),
    });
    const page = await designer();
    fireEvent.keyDown(await within(page).findByRole("button", { name: "Total" }), { key: "Enter" });
    fireEvent.change(within(page).getByLabelText("Entity on the other side"), { target: { value: "order" } });

    fireEvent.click(within(page).getByRole("button", { name: "Save draft" }));

    expect(await within(page).findByRole("alert")).toHaveTextContent("The definition is not valid.");
    const put = calls.find((call) => call.key === "PUT /api/v1/integrations/link-a/mappings/Order__c");
    const sent = JSON.parse(put?.body ?? "{}");
    expect(sent.entity).toBe("order");
    expect(sent.definition.fields[0]).toMatchObject({ platform: "total__c", external: "total" });
    expect(sent.version).toBe(0);
  });

  it("tries a sample with the real mapper and shows what it gave", async () => {
    api({
      "POST /api/v1/integrations/link-a/mappings/Order__c/dry-run": () =>
        json({ data: { direction: "OUTBOUND", result: { total: "12.50" }, skipped: [], problems: {} } }),
    });
    const page = await designer();
    fireEvent.keyDown(await within(page).findByRole("button", { name: "Total" }), { key: "Enter" });
    fireEvent.change(within(page).getByLabelText("Sample total__c"), { target: { value: "12.50" } });

    await act(async () => {
      fireEvent.click(within(page).getByRole("button", { name: "Try it" }));
    });

    expect(await within(page).findByTestId("mapping-dry-run")).toHaveTextContent("12.50");
  });

  it("publishes the saved draft and lists the versions the API sends", async () => {
    const definition = { conflict: "rule-1", inboundCreate: false, inboundUpdate: false, fields: [] };
    const live = { id: "v1", number: 1, status: "LIVE", hash: "h", version: 1, publishedAt: "2026-10-30T10:00:00Z", definition };
    const draft = {
      id: "v2",
      number: 2,
      status: "DRAFT",
      hash: "h2",
      version: 3,
      definition: {
        ...definition,
        fields: [
          { external: "total", platform: "total__c", direction: "dir-out", externalType: "type-1", required: false, conversion: "conv-1", valueMap: [] },
        ],
      },
    };
    const calls = api({
      "GET /api/v1/integrations/link-a/mappings": () =>
        json({ data: [{ id: "m", connection: "link-a", object: "Order__c", entity: "order", live, draft, history: [], canPublish: true }] }),
      "POST /api/v1/integrations/link-a/mappings/Order__c/publish": () => json({ data: {} }),
    });
    const page = await designer();

    expect(await within(page).findByTestId("mapping-versions")).toHaveTextContent("Version 2: draft");
    expect(within(page).getByTestId("mapping-versions")).toHaveTextContent("Version 1: live");
    fireEvent.click(within(page).getByRole("button", { name: "Publish" }));

    await waitFor(() => expect(calls.some((call) => call.key.endsWith("/Order__c/publish"))).toBe(true));
    expect(JSON.parse(calls.find((call) => call.key.endsWith("/Order__c/publish"))?.body ?? "{}")).toEqual({ version: 3 });
  });

  it("shows the sends as the API lists them, with its words and never a value", async () => {
    api({
      "GET /api/v1/integrations/link-a/deliveries": () =>
        json({
          data: [
            {
              id: "d1",
              direction: "OUT",
              kind: "CREATE",
              object: "Order__c",
              state: "FAILED",
              reason: "any-key",
              words: "The service did not accept the record.",
              createdAt: "2026-10-30T10:00:00Z",
              stale: false,
            },
          ],
        }),
    });
    const page = await designer();

    expect(await within(page).findByTestId("mapping-deliveries")).toHaveTextContent("The service did not accept the record.");
  });

  it("says so when the connection may touch nothing yet", async () => {
    api();
    render(<MappingDesigner connection={{ ...CONNECTION, scope: [] }} />);

    expect(await screen.findByText(/Give the connection something to touch/)).toBeInTheDocument();
  });
});
