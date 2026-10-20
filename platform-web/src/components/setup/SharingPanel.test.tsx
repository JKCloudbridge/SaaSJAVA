import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { currentUrl, setUrl } from "@/test/navigation";
import { tabProps } from "@/test/objectTab";
import { SharingPanel } from "./SharingPanel";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const SECTION = "/setup/objects/Deal__c?section=sharing";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
  window.sessionStorage.clear();
  setUrl(SECTION);
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const ROLE = "0198a1b2-0000-7000-8000-000000000011";
const OTHER_ROLE = "0198a1b2-0000-7000-8000-000000000012";
const GROUP = "0198a1b2-0000-7000-8000-000000000021";

function field(apiName: string, extra: Record<string, unknown> = {}) {
  return {
    apiName, label: apiName, description: "", kind: "CUSTOM", type: "TEXT", required: false, unique: false,
    settings: {}, retired: false, editable: true, version: 0, ...extra,
  };
}

const DEAL = {
  apiName: "Deal__c", label: "Deal", pluralLabel: "Deals", description: "", kind: "CUSTOM", editable: true,
  extensible: true, version: 0,
  fields: [
    field("id", { kind: "SYSTEM", editable: false }),
    field("stage__c", { label: "Stage", indexed: true }),
    field("open__c", { label: "Open", type: "BOOLEAN", indexed: true }),
    field("note__c", { label: "Note" }),
  ],
};
const UNSET = { object: "Deal__c", defaultAccess: "PUBLIC_READ_WRITE", grantViaHierarchy: true, configured: false };
const SET_UP = { object: "Deal__c", defaultAccess: "PRIVATE", grantViaHierarchy: false, configured: true, version: 6 };
const OWNER_RULE = {
  id: "0198a1b2-0000-7000-8000-0000000000a1", name: "Managers see team deals", object: "Deal__c", kind: "OWNER",
  sourceType: "ROLE_AND_SUBORDINATES", sourceId: ROLE, targetType: "ROLE", targetId: OTHER_ROLE, accessLevel: "READ",
  active: true, version: 2,
};
const CRITERIA_RULE = {
  id: "0198a1b2-0000-7000-8000-0000000000a2", name: "Open deals", object: "Deal__c", kind: "CRITERIA",
  criterion: { op: "AND", items: [{ field: "stage__c", op: "EQ", values: ["Open"] }, { field: "open__c", op: "EQ", values: [true] }] },
  targetType: "GROUP", targetId: GROUP, accessLevel: "READ_WRITE", active: false, version: 1,
};

function answers(sharing: unknown, rules: unknown[], extra: Record<string, () => Response> = {}) {
  return {
    "GET /api/v1/sharing/objects/Deal__c": () => json({ data: sharing }),
    "GET /api/v1/sharing/rules": () => json({ data: rules }),
    "GET /api/v1/roles": () => json({ data: [{ id: ROLE, name: "Managers" }, { id: OTHER_ROLE, name: "Analysts" }] }),
    "GET /api/v1/groups": () => json({ data: [{ id: GROUP, name: "Partners", description: "", groups: [], people: [], policies: [] }] }),
    ...extra,
  };
}

function form(name: string) {
  return within(screen.getByRole("form", { name }));
}

const panel = (object: unknown = DEAL) => <SharingPanel {...tabProps(object)} />;

async function openRule(name: string) {
  fireEvent.click(await screen.findByRole("button", { name }));
  return screen.findByTestId("sharing-rule-detail");
}

describe("SharingPanel", () => {
  it("says that sharing is live and not part of a change set, and that an object nobody set up is open to every reader, under React strict mode", async () => {
    fakeApi(answers(UNSET, []));

    render(<StrictMode>{panel()}</StrictMode>);

    expect(await screen.findByTestId("sharing-live-note")).toHaveTextContent("not part of a change set");
    expect(screen.getByRole("heading", { name: "Deal — Sharing" })).toBeInTheDocument();
    expect(screen.getByText(/Nobody has set this object up yet/)).toBeInTheDocument();
    expect(form("Default access").getByLabelText("Default access of the organization")).toHaveValue("PUBLIC_READ_WRITE");
    expect(screen.getByText(/No sharing rules yet/)).toBeInTheDocument();
  });

  it("saves the default access and the hierarchy switch with the version it read", async () => {
    const calls = fakeApi(
      answers(SET_UP, [], { "PUT /api/v1/sharing/objects/Deal__c": () => json({ data: SET_UP }) }),
    );
    render(panel());
    await screen.findByRole("form", { name: "Default access" });
    const defaults = form("Default access");

    expect(defaults.getByLabelText("Default access of the organization")).toHaveValue("PRIVATE");
    fireEvent.change(defaults.getByLabelText("Default access of the organization"), { target: { value: "PUBLIC_READ" } });
    fireEvent.click(defaults.getByLabelText(/Give access through the role hierarchy/));
    fireEvent.click(defaults.getByRole("button", { name: "Save the default access" }));

    expect(await screen.findByTestId("sharing-notice")).toHaveTextContent("It applies to every member now.");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/sharing/objects/Deal__c")?.body ?? "{}");
    expect(sent).toEqual({ defaultAccess: "PUBLIC_READ", grantViaHierarchy: true, version: 6 });
  });

  it("sends no version for an object nobody has set up", async () => {
    const calls = fakeApi(answers(UNSET, [], { "PUT /api/v1/sharing/objects/Deal__c": () => json({ data: SET_UP }) }));
    render(panel());
    await screen.findByText(/Nobody has set this object up yet/);
    const defaults = form("Default access");

    fireEvent.change(defaults.getByLabelText("Default access of the organization"), { target: { value: "PRIVATE" } });
    fireEvent.click(defaults.getByRole("button", { name: "Save the default access" }));

    await screen.findByTestId("sharing-notice");
    const sent = JSON.parse(calls.find((call) => call.key === "PUT /api/v1/sharing/objects/Deal__c")?.body ?? "{}");
    expect(sent).toEqual({ defaultAccess: "PRIVATE", grantViaHierarchy: true });
  });

  it("lists the rules in words: who owns the records, who gets them, at which level", async () => {
    fakeApi(answers(SET_UP, [OWNER_RULE, CRITERIA_RULE]));
    render(panel());

    const rows = await screen.findAllByTestId("sharing-rule-row");
    expect(rows[0]).toHaveTextContent("Managers see team deals");
    expect(rows[0]).toHaveTextContent("By owner");
    expect(rows[0]).toHaveTextContent("Records owned by Managers and below");
    expect(rows[0]).toHaveTextContent("Analysts");
    expect(rows[0]).toHaveTextContent("Read only");
    expect(rows[1]).toHaveTextContent("Records where Stage is Open and Open is true");
    expect(rows[1]).toHaveTextContent("Partners");
    expect(rows[1]).toHaveTextContent("Read and write");
    expect(rows[1]).toHaveTextContent("Off");
  });

  it("opens a rule's page with the rule in words", async () => {
    fakeApi(answers(SET_UP, [OWNER_RULE]));
    render(panel());

    const page = await openRule("Managers see team deals");

    expect(currentUrl()).toBe(`${SECTION}&rule=${OWNER_RULE.id}`);
    expect(page).toHaveTextContent("Records owned by Managers and below");
    expect(page).toHaveTextContent("Analysts");
    expect(page).toHaveTextContent("Read only");
    expect(page).toHaveTextContent("On");
  });

  it("adds a rule by owner, from the form behind New", async () => {
    const calls = fakeApi(answers(SET_UP, [], { "POST /api/v1/sharing/rules": () => json({ data: OWNER_RULE }, 201) }));
    render(panel());
    await screen.findByText(/No sharing rules yet/);

    fireEvent.click(screen.getByRole("button", { name: "New" }));
    const add = form("New sharing rule");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Managers see team deals" } });
    fireEvent.change(add.getByLabelText("Owned by"), { target: { value: "ROLE_AND_SUBORDINATES" } });
    fireEvent.change(add.getByLabelText("Role"), { target: { value: ROLE } });
    fireEvent.change(add.getByLabelText("Role that gets access"), { target: { value: OTHER_ROLE } });
    fireEvent.click(screen.getByRole("button", { name: "Add the rule" }));

    expect(await screen.findByTestId("sharing-notice")).toHaveTextContent("The rule was added.");
    expect(currentUrl()).toBe(SECTION);
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/sharing/rules")?.body ?? "{}");
    expect(sent).toEqual({
      name: "Managers see team deals", kind: "OWNER", object: "Deal__c", sourceType: "ROLE_AND_SUBORDINATES",
      sourceId: ROLE, targetType: "ROLE", targetId: OTHER_ROLE, accessLevel: "READ", active: true,
    });
  });

  it("adds a rule by condition on indexed fields only, with a checkbox compared as true or false", async () => {
    const calls = fakeApi(answers(SET_UP, [], { "POST /api/v1/sharing/rules": () => json({ data: CRITERIA_RULE }, 201) }));
    render(panel());
    await screen.findByText(/No sharing rules yet/);

    fireEvent.click(screen.getByRole("button", { name: "New" }));
    const add = form("New sharing rule");
    fireEvent.change(add.getByLabelText("Name"), { target: { value: "Open deals" } });
    fireEvent.change(add.getByLabelText("Which records"), { target: { value: "CRITERIA" } });
    const fieldSelect = add.getByLabelText("Field");
    expect(within(fieldSelect).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Choose a field", "Stage", "Open",
    ]);
    fireEvent.change(fieldSelect, { target: { value: "stage__c" } });
    fireEvent.change(add.getByLabelText("Value"), { target: { value: "Open" } });
    fireEvent.click(add.getByRole("button", { name: "Add a condition" }));
    fireEvent.change(add.getAllByLabelText("Field")[1] as HTMLElement, { target: { value: "open__c" } });
    fireEvent.change(add.getAllByLabelText("Value")[1] as HTMLElement, { target: { value: "true" } });
    fireEvent.change(add.getByLabelText("Shared with"), { target: { value: "GROUP" } });
    fireEvent.change(add.getByLabelText("Public group that gets access"), { target: { value: GROUP } });
    fireEvent.change(add.getByLabelText("Access"), { target: { value: "READ_WRITE" } });
    fireEvent.click(add.getByLabelText("The rule is on"));
    fireEvent.click(screen.getByRole("button", { name: "Add the rule" }));

    await screen.findByTestId("sharing-notice");
    const sent = JSON.parse(calls.find((call) => call.key === "POST /api/v1/sharing/rules")?.body ?? "{}");
    expect(sent.kind).toBe("CRITERIA");
    expect(sent.criterion).toEqual({
      op: "AND",
      items: [
        { field: "stage__c", op: "EQ", values: ["Open"] },
        { field: "open__c", op: "EQ", values: [true] },
      ],
    });
    expect(sent.targetType).toBe("GROUP");
    expect(sent.active).toBe(false);
    expect(sent.sourceId).toBeUndefined();
  });

  it("changes a rule with the version it read and keeps its type fixed", async () => {
    const calls = fakeApi(
      answers(SET_UP, [OWNER_RULE], { [`PUT /api/v1/sharing/rules/${OWNER_RULE.id}`]: () => json({ data: OWNER_RULE }) }),
    );
    render(panel());
    const page = await openRule("Managers see team deals");

    fireEvent.click(within(page).getByRole("button", { name: "Edit" }));
    const change = form("Change the sharing rule");
    expect(change.queryByLabelText("Which records")).toBeNull();
    fireEvent.change(change.getByLabelText("Access"), { target: { value: "READ_WRITE" } });
    fireEvent.click(screen.getByRole("button", { name: "Save the rule" }));

    expect(await screen.findByTestId("sharing-notice")).toHaveTextContent("The rule was saved.");
    const sent = JSON.parse(calls.find((call) => call.key === `PUT /api/v1/sharing/rules/${OWNER_RULE.id}`)?.body ?? "{}");
    expect(sent.version).toBe(2);
    expect(sent.accessLevel).toBe("READ_WRITE");
    expect(sent.kind).toBe("OWNER");
  });

  it("keeps a condition the form cannot edit as it is, until a new one is started", async () => {
    const nested = {
      ...CRITERIA_RULE,
      criterion: { op: "OR", items: [{ op: "AND", items: [{ field: "stage__c", op: "EQ", values: ["A"] }] }] },
    };
    const calls = fakeApi(answers(SET_UP, [nested], { [`PUT /api/v1/sharing/rules/${nested.id}`]: () => json({ data: nested }) }));
    render(panel());
    const page = await openRule("Open deals");
    fireEvent.click(within(page).getByRole("button", { name: "Edit" }));
    const change = form("Change the sharing rule");

    expect(change.getByRole("button", { name: "Start a new condition" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save the rule" }));

    await screen.findByTestId("sharing-notice");
    const sent = JSON.parse(calls.find((call) => call.key === `PUT /api/v1/sharing/rules/${nested.id}`)?.body ?? "{}");
    expect(sent.criterion).toEqual(nested.criterion);
  });

  it("removes a rule only after a second click, then goes back to the list", async () => {
    const calls = fakeApi(
      answers(SET_UP, [OWNER_RULE], { [`DELETE /api/v1/sharing/rules/${OWNER_RULE.id}`]: () => new Response(null, { status: 204 }) }),
    );
    render(panel());
    const page = await openRule("Managers see team deals");

    fireEvent.click(within(page).getByRole("button", { name: "Remove" }));
    expect(calls.some((call) => call.key.startsWith("DELETE"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, remove the rule" }));

    expect(await screen.findByTestId("sharing-notice")).toHaveTextContent("The rule was removed.");
    expect(currentUrl()).toBe(SECTION);
    expect(calls.some((call) => call.key === `DELETE /api/v1/sharing/rules/${OWNER_RULE.id}`)).toBe(true);
  });

  it("shows the words of the API with the place when a rule is refused, and stays on the form", async () => {
    fakeApi(
      answers(SET_UP, [], {
        "POST /api/v1/sharing/rules": () =>
          json(
            {
              error: {
                code: "VALIDATION_ERROR",
                message: "The request has problems.",
                fields: { "criterion.items[0].field": ["Is not a field that is marked as indexed."] },
              },
            },
            400,
          ),
      }),
    );
    render(panel());
    await screen.findByText(/No sharing rules yet/);
    fireEvent.click(screen.getByRole("button", { name: "New" }));
    fireEvent.click(screen.getByRole("button", { name: "Add the rule" }));

    expect(await screen.findByTestId("sharing-problem")).toHaveTextContent("Is not a field that is marked as indexed.");
    expect(screen.getByRole("form", { name: "New sharing rule" })).toBeInTheDocument();
  });

  it("shows the API's words for a person who may not manage sharing", async () => {
    fakeApi({
      "GET /api/v1/sharing/objects/Deal__c": () => refusal("FORBIDDEN", "You are not allowed to do this.", 403),
      "GET /api/v1/sharing/rules": () => refusal("FORBIDDEN", "You are not allowed to do this.", 403),
      "GET /api/v1/roles": () => json({ data: [] }),
      "GET /api/v1/groups": () => json({ data: [] }),
    });
    render(panel());

    expect(await screen.findByTestId("sharing-failed")).toHaveTextContent("You are not allowed to do this.");
  });

  it("says that the sharing of a platform object is the platform's", () => {
    fakeApi({});
    render(panel({ ...DEAL, managedBy: "identity" }));

    expect(screen.getByText(/sharing is the platform/)).toBeInTheDocument();
  });
});
