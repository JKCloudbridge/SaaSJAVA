import { beforeEach, describe, expect, it, vi } from "vitest";
import { applicationOfObject, roleOfObject, rolesOfApplication } from "@/lib/applications/objectRoles";
import { recordObjectsOf } from "./objects";
import { scope, scopeQuery, setScope } from "./scope";

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("@/lib/api/client", () => ({ api: { GET: get, POST: get, PUT: get } }));

describe("the objects an application of the kind uses (FA13B, ADR-0226)", () => {
  it("are read from what the API lists for the application, one set for each", () => {
    expect(recordObjectsOf(rolesOfApplication("inspection"))).toEqual({
      template: "InspectionTemplate",
      fill: "InspectionFill",
      changeRequest: "InspectionChangeRequest",
    });
    expect(recordObjectsOf(rolesOfApplication("environment-management"))).toEqual({
      template: "EnvironmentTemplate",
      fill: "EnvironmentFill",
      changeRequest: "EnvironmentChangeRequest",
    });
  });

  it("are empty for an application the member does not have, never a guess", () => {
    expect(recordObjectsOf(rolesOfApplication("no-such-application"))).toEqual({
      template: "",
      fill: "",
      changeRequest: "",
    });
  });

  it("tell which part an object plays and which application it belongs to", () => {
    expect(roleOfObject("EnvironmentChangeRequest")).toBe("changeRequest");
    expect(roleOfObject("Expense__c")).toBeUndefined();
    expect(applicationOfObject("EnvironmentFill")).toBe("environment-management");
  });
});

describe("the application a call names", () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ data: { data: { records: [] } }, response: new Response(null, { status: 200 }) });
  });

  it("is the application of the page that is drawn, read when the call is made", async () => {
    const { listTemplates, NO_FILTERS } = await import("./api");

    setScope("environment-management");
    await listTemplates(NO_FILTERS);
    setScope("inspection");
    await listTemplates(NO_FILTERS);

    const asked = get.mock.calls.filter((call) => call[0] === "/api/v1/templates").map((call) => (call[1] as { params: { query: { application: string } } }).params.query);
    expect(asked.map((query) => query.application)).toEqual(["environment-management", "inspection"]);
    expect(scope()).toBe("inspection");
    expect(scopeQuery()).toEqual({ application: "inspection" });
  });
});
