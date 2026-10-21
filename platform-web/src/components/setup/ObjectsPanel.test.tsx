import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { ObjectsPanel } from "./ObjectsPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  setUrl("/setup/objects");
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
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
  it("lists the objects with label, API name and a Standard or Custom tag, under React strict mode", async () => {
    const calls = fakeApi({ "GET /api/v1/metadata/objects": () => json(LIST) });
    render(
      <StrictMode>
        <ObjectsPanel />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("object-row");

    expect(screen.getByRole("heading", { name: "Object settings" })).toBeInTheDocument();
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Account");
    expect(rows[0]).toHaveTextContent("Standard");
    expect(rows[1]).toHaveTextContent("Employee__c");
    expect(rows[1]).toHaveTextContent("Custom");
    expect(within(rows[1] as HTMLElement).getByRole("link", { name: "Employee" })).toHaveAttribute(
      "href",
      "/setup/objects/Employee__c",
    );
    // The description paragraphs and the counts of the old long page are gone.
    expect(screen.queryByText(/Standard object/)).not.toBeInTheDocument();
    expect(screen.queryByText(/added by the organization/)).not.toBeInTheDocument();
    expect(calls.filter((call) => call.key === "GET /api/v1/metadata/objects").length).toBeGreaterThan(0);
  });

  it("searches the loaded objects by label, API name and type", async () => {
    fakeApi({ "GET /api/v1/metadata/objects": () => json(LIST) });
    render(<ObjectsPanel />);
    await screen.findAllByTestId("object-row");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search objects" }), { target: { value: "custom" } });

    expect(screen.getAllByTestId("object-row")).toHaveLength(1);
    expect(screen.getByText("Showing 1 of 3.")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("searchbox", { name: "Search objects" }), { target: { value: "zzz" } });
    expect(screen.getByText("Nothing matches “zzz”.")).toBeInTheDocument();
  });

  it("opens the create form behind New and goes back to the list with a notice when the object was created", async () => {
    const calls = fakeApi({
      "GET /api/v1/metadata/objects": () => json(LIST),
      "POST /api/v1/metadata/objects": () => json({ data: LIST.data[1] }, 201),
    });
    render(<ObjectsPanel />);
    await screen.findAllByTestId("object-row");

    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "New" }));
    expect(currentUrl()).toBe("/setup/objects?new=1");

    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Vehicle" } });
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Vehicle" } });
    fireEvent.change(screen.getByLabelText("Plural label"), { target: { value: "Vehicles" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the object" }));

    expect(await screen.findByTestId("objects-notice")).toHaveTextContent("The object was created.");
    expect(currentUrl()).toBe("/setup/objects");
    expect(calls.find((call) => call.key === "POST /api/v1/metadata/objects")?.body).toBe(
      JSON.stringify({ name: "Vehicle", label: "Vehicle", pluralLabel: "Vehicles", description: "" }),
    );
  });

  it("puts the new object into the chosen change set instead of making it live", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-1");
    const calls = fakeApi({
      "GET /api/v1/metadata/objects": () => json(LIST),
      "POST /api/v1/metadata/change-sets/set-1/changes": () => json({ data: {} }, 201),
    });
    setUrl("/setup/objects?new=1");
    render(<ObjectsPanel />);

    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Vehicle" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the object" }));

    expect(await screen.findByTestId("objects-notice")).toHaveTextContent("added to the change set");
    expect(calls.some((call) => call.key === "POST /api/v1/metadata/objects")).toBe(false);
    expect(calls.find((call) => call.key === "POST /api/v1/metadata/change-sets/set-1/changes")?.body).toContain(
      "\"objectApiName\":\"Vehicle__c\"",
    );
  });

  it("shows the API's own words, with the invalid fields named, when an object is refused, and stays on the form", async () => {
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
    setUrl("/setup/objects?new=1");
    render(<ObjectsPanel />);

    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Employee" } });
    fireEvent.click(screen.getByRole("button", { name: "Create the object" }));

    const problem = await screen.findByTestId("objects-problem");
    expect(problem).toHaveTextContent("Name: An object with this name exists already.");
    expect(currentUrl()).toBe("/setup/objects?new=1");
  });

  it("says what the API answered when the caller may not view objects", async () => {
    fakeApi({
      "GET /api/v1/metadata/objects": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<ObjectsPanel />);

    expect(await screen.findByTestId("objects-failed")).toHaveTextContent("You are not allowed to perform this action.");
  });
});
