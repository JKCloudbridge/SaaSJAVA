import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ObjectsPanel } from "./ObjectsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const LIST = {
  data: [
    { apiName: "Account", label: "Account", pluralLabel: "Accounts", kind: "STANDARD", fieldCount: 29, customFieldCount: 1 },
    { apiName: "Employee__c", label: "Employee", pluralLabel: "Employees", kind: "CUSTOM", fieldCount: 10, customFieldCount: 2 },
    {
      apiName: "User",
      label: "User",
      pluralLabel: "Users",
      kind: "STANDARD",
      managedBy: "identity",
      fieldCount: 19,
      customFieldCount: 0,
    },
  ],
};

describe("ObjectsPanel", () => {
  it("lists the standard and the custom objects as the API reports them, under React strict mode", async () => {
    const calls = fakeApi({ "GET /api/v1/metadata/objects": () => json(LIST) });
    render(
      <StrictMode>
        <ObjectsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("object-row");

    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Account");
    expect(rows[0]).toHaveTextContent("Standard");
    expect(rows[0]).toHaveTextContent("1 added by the organization");
    expect(rows[1]).toHaveTextContent("Employee__c");
    expect(rows[1]).toHaveTextContent("Custom");
    expect(rows[2]).toHaveTextContent("Records managed by identity");
    expect(within(rows[1] as HTMLElement).getByRole("link", { name: "Employee" })).toHaveAttribute(
      "href",
      "/setup/objects/Employee__c",
    );
    expect(calls.filter((call) => call.key === "GET /api/v1/metadata/objects").length).toBeGreaterThan(0);
  });

  it("creates an object from the typed name and labels, and shows the new list", async () => {
    const calls = fakeApi({
      "GET /api/v1/metadata/objects": () => json(LIST),
      "POST /api/v1/metadata/objects": () => json({ data: LIST.data[1] }, 201),
    });
    render(<ObjectsPanel />);
    await screen.findAllByTestId("object-row");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Vehicle" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Vehicle" } });
    fireEvent.change(screen.getByLabelText("Plural label"), { target: { value: "Vehicles" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the object" }));

    expect(await screen.findByTestId("objects-notice")).toHaveTextContent("The object was created.");
    expect(calls.find((call) => call.key === "POST /api/v1/metadata/objects")?.body).toBe(
      JSON.stringify({ name: "Vehicle", label: "Vehicle", pluralLabel: "Vehicles", description: "" }),
    );
  });

  it("shows the API's own words, with the invalid fields named, when an object is refused", async () => {
    fakeApi({
      "GET /api/v1/metadata/objects": () => json(LIST),
      "POST /api/v1/metadata/objects": () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is invalid.",
              fields: { name: ["An object with this name exists already."] },
            },
          },
          400,
        ),
    });
    render(<ObjectsPanel />);
    await screen.findAllByTestId("object-row");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Employee" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the object" }));

    const problem = await screen.findByTestId("objects-problem");
    expect(problem).toHaveTextContent("Name: An object with this name exists already.");
  });

  it("says what the API answered when the caller may not view objects", async () => {
    fakeApi({
      "GET /api/v1/metadata/objects": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<ObjectsPanel />);

    expect(await screen.findByTestId("objects-failed")).toHaveTextContent("You are not allowed to perform this action.");
  });
});
