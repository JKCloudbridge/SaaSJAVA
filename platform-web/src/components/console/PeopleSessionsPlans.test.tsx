import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { PeoplePanel } from "./PeoplePanel";
import { PlansPanel } from "./PlansPanel";
import { SessionsPanel } from "./SessionsPanel";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

describe("PeoplePanel", () => {
  const PEOPLE = {
    data: [
      { id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", email: "platform-a@example.test", displayName: "Platform A", role: "PLATFORM_ADMIN", since: "2026-10-01T10:00:00Z" },
      { id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", email: "support-a@example.test", displayName: "Support A", role: "PLATFORM_SUPPORT", since: "2026-10-01T11:00:00Z" },
    ],
  };

  it("lists, grants and takes away roles", async () => {
    const calls = fakeApi({
      "GET /api/v1/platform/people": () => json(PEOPLE),
      "POST /api/v1/platform/people": () => json({ data: PEOPLE.data[1] }, 201),
    });
    render(<PeoplePanel />);
    const rows = await screen.findAllByTestId("person-row");
    expect(rows).toHaveLength(2);

    fireEvent.change(screen.getByLabelText("E-mail address"), { target: { value: "billing-a@example.test" } });
    fireEvent.change(screen.getByLabelText("Role"), { target: { value: "PLATFORM_BILLING" } });
    fireEvent.submit(screen.getByRole("form", { name: "Give a platform role" }));

    expect(await screen.findByTestId("people-notice")).toHaveTextContent("The role was granted.");
    expect(calls.find((call) => call.key === "POST /api/v1/platform/people")?.body).toBe(
      JSON.stringify({ email: "billing-a@example.test", role: "PLATFORM_BILLING" }),
    );
    fireEvent.click(within(rows[1] as HTMLElement).getByRole("button", { name: "Take away" }));
    await waitFor(() =>
      expect(calls.map((call) => call.key)).toContain(
        "DELETE /api/v1/platform/people/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      ),
    );
  });

  it("repeats the API's words when the last platform administrator cannot be removed", async () => {
    fakeApi({
      "GET /api/v1/platform/people": () => json(PEOPLE),
      "DELETE /api/v1/platform/people/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa": () =>
        refusal("CONFLICT", "The last platform administrator cannot be removed. Grant the role to another person first.", 409),
    });
    render(<PeoplePanel />);
    const rows = await screen.findAllByTestId("person-row");

    fireEvent.click(within(rows[0] as HTMLElement).getByRole("button", { name: "Take away" }));

    expect(await screen.findByTestId("people-problem")).toHaveTextContent("last platform administrator");
  });

  it("shows only the refusal to a caller who is not a platform administrator", async () => {
    fakeApi({
      "GET /api/v1/platform/people": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<PeoplePanel />);

    expect(await screen.findByTestId("people-failed")).toHaveTextContent("not allowed");
    expect(screen.queryByTestId("person-row")).toBeNull();
  });
});

describe("SessionsPanel", () => {
  it("looks a person up by the address in the body and shows no token", async () => {
    const calls = fakeApi({
      "POST /api/v1/platform/sessions/lookup": () =>
        json({ data: [{ kind: "TOKENS", organization: "tenant-a", started: "2026-10-02T09:00:00Z", expires: "2026-10-02T17:00:00Z" }] }),
    });
    render(<SessionsPanel />);

    fireEvent.change(screen.getByLabelText("E-mail address of the person"), { target: { value: "user-a@example.test" } });
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "suspected misuse" } });
    fireEvent.click(screen.getByRole("button", { name: "Look at the sign-ins" }));

    const table = await screen.findByTestId("sessions");
    expect(table).toHaveTextContent("tenant-a");
    expect(table.textContent).not.toMatch(/secret|hash/i);
    const call = calls.find((c) => c.key === "POST /api/v1/platform/sessions/lookup");
    expect(call?.key).not.toContain("user-a");
    expect(call?.body).toBe(JSON.stringify({ email: "user-a@example.test", reason: "suspected misuse" }));
  });

  it("says the same for a person with nothing and signs a person out everywhere", async () => {
    const calls = fakeApi({ "POST /api/v1/platform/sessions/lookup": () => json({ data: [] }) });
    render(<SessionsPanel />);
    fireEvent.change(screen.getByLabelText("E-mail address of the person"), { target: { value: "nobody@example.test" } });
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "check" } });

    fireEvent.click(screen.getByRole("button", { name: "Look at the sign-ins" }));
    expect(await screen.findByTestId("sessions-none")).toHaveTextContent("No live sign-ins.");

    fireEvent.click(screen.getByRole("button", { name: "Sign the person out everywhere" }));
    expect(await screen.findByTestId("sessions-notice")).toHaveTextContent("signed out everywhere");
    expect(calls.map((c) => c.key)).toContain("POST /api/v1/platform/sessions/sign-out");
  });

  it("shows the API's refusal to a role that may not", async () => {
    fakeApi({
      "POST /api/v1/platform/sessions/lookup": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<SessionsPanel />);
    fireEvent.change(screen.getByLabelText("E-mail address of the person"), { target: { value: "user-a@example.test" } });
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "check" } });

    fireEvent.click(screen.getByRole("button", { name: "Look at the sign-ins" }));

    expect(await screen.findByTestId("sessions-problem")).toHaveTextContent("not allowed");
  });
});

describe("PlansPanel", () => {
  const catalogue = () => ({
    "GET /api/v1/platform/plans": () =>
      json({ data: [{ key: "trial", name: "Trial", trialDays: 30, licences: { user: 5, admin: 2 }, features: ["approvals"] }] }),
    "GET /api/v1/platform/licence-types": () => json({ data: [{ key: "user", name: "User" }, { key: "admin", name: "Administrator" }] }),
    "GET /api/v1/platform/features": () => json({ data: [{ key: "approvals", name: "Approvals" }, { key: "workflows", name: "Workflows" }] }),
  });

  it("lists the plans and saves one with the quantities and features that were chosen", async () => {
    const calls = fakeApi({
      ...catalogue(),
      "PUT /api/v1/platform/plans/plan-b": () => json({ data: { key: "plan-b", name: "Plan B", licences: {}, features: [] } }),
    });
    render(<PlansPanel />);

    const row = await screen.findByTestId("plan-row");
    expect(row).toHaveTextContent("30 days");
    expect(row).toHaveTextContent("user: 5, admin: 2");

    fireEvent.change(screen.getByLabelText(/^Key/, { selector: "#plan-key" }), { target: { value: "plan-b" } });
    fireEvent.change(screen.getByLabelText("Name", { selector: "#plan-name" }), { target: { value: "Plan B" } });
    fireEvent.change(screen.getByLabelText("User licences"), { target: { value: "10" } });
    fireEvent.click(screen.getByLabelText("Workflows"));
    fireEvent.submit(screen.getByRole("form", { name: "Create or replace a plan" }));

    expect(await screen.findByTestId("plans-notice")).toHaveTextContent("The plan was saved.");
    expect(calls.find((call) => call.key === "PUT /api/v1/platform/plans/plan-b")?.body).toBe(
      JSON.stringify({ name: "Plan B", licences: { user: 10 }, features: ["workflows"] }),
    );
  });

  it("shows the API's words when changing is refused", async () => {
    fakeApi({
      ...catalogue(),
      "PUT /api/v1/platform/plans/plan-b": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<PlansPanel />);
    await screen.findByTestId("plan-row");
    fireEvent.change(screen.getByLabelText(/^Key/, { selector: "#plan-key" }), { target: { value: "plan-b" } });
    fireEvent.change(screen.getByLabelText("Name", { selector: "#plan-name" }), { target: { value: "Plan B" } });

    fireEvent.submit(screen.getByRole("form", { name: "Create or replace a plan" }));

    expect(await screen.findByTestId("plans-problem")).toHaveTextContent("not allowed");
  });

  it("adds a licence type and a feature", async () => {
    const calls = fakeApi({
      ...catalogue(),
      "POST /api/v1/platform/licence-types": () => json({ data: { key: "guest", name: "Guest" } }, 201),
      "POST /api/v1/platform/features": () => json({ data: { key: "reports", name: "Reports" } }, 201),
    });
    render(<PlansPanel />);
    await screen.findByTestId("plan-row");

    fireEvent.change(screen.getByLabelText("Key", { selector: "#type-key" }), { target: { value: "guest" } });
    fireEvent.change(screen.getByLabelText("Name", { selector: "#type-name" }), { target: { value: "Guest" } });
    fireEvent.submit(screen.getByRole("form", { name: "Add a licence type" }));
    await waitFor(() => expect(calls.map((c) => c.key)).toContain("POST /api/v1/platform/licence-types"));
    fireEvent.change(screen.getByLabelText("Key", { selector: "#feature-key" }), { target: { value: "reports" } });
    fireEvent.change(screen.getByLabelText("Name", { selector: "#feature-name" }), { target: { value: "Reports" } });
    fireEvent.submit(screen.getByRole("form", { name: "Add a feature" }));
    await waitFor(() => expect(calls.map((c) => c.key)).toContain("POST /api/v1/platform/features"));
  });
});
