import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ProvisionForm } from "./ProvisionForm";

const PLANS = {
  data: [
    { key: "trial", name: "Trial", trialDays: 30, licences: { user: 5 }, features: ["approvals"] },
    { key: "plan-a", name: "Plan A", licences: { user: 20 }, features: [] },
  ],
};

function fill(email = "first-admin@example.test") {
  fireEvent.change(screen.getByLabelText("Organization name"), { target: { value: "Client A" } });
  fireEvent.change(screen.getByLabelText("E-mail address of the first administrator"), { target: { value: email } });
}

describe("ProvisionForm", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("sends the name, the suggested short name, the plan and the address, and says the organization stays closed", async () => {
    const calls = fakeApi({
      "GET /api/v1/platform/plans": () => json(PLANS),
      "POST /api/v1/platform/organizations": () =>
        json(
          {
            data: {
              id: "33333333-3333-4333-8333-333333333333",
              slug: "client-a",
              displayName: "Client A",
              status: "PROVISIONING",
              trialExpired: false,
            },
          },
          201,
        ),
    });
    render(<ProvisionForm />);
    await screen.findByRole("option", { name: "Plan A" });

    fill();
    fireEvent.change(screen.getByLabelText("Plan"), { target: { value: "plan-a" } });
    fireEvent.click(screen.getByRole("button", { name: "Set up the organization" }));

    const done = await screen.findByTestId("provisioned");
    expect(done).toHaveTextContent("stays closed until its first administrator accepts");
    expect(done).not.toHaveTextContent("first-admin@example.test");
    expect(calls.find((call) => call.key === "POST /api/v1/platform/organizations")?.body).toBe(
      JSON.stringify({
        displayName: "Client A",
        slug: "client-a",
        planKey: "plan-a",
        email: "first-admin@example.test",
      }),
    );
    expect(screen.getByRole("link", { name: "Open the organization" })).toHaveAttribute(
      "href",
      "/console/organizations/33333333-3333-4333-8333-333333333333",
    );
  });

  it("shows the API's own words for a taken name", async () => {
    fakeApi({
      "GET /api/v1/platform/plans": () => json(PLANS),
      "POST /api/v1/platform/organizations": () =>
        json(
          { error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { slug: ["Is not available."] } } },
          400,
        ),
    });
    render(<ProvisionForm />);
    await screen.findByRole("option", { name: "Plan A" });

    fill();
    fireEvent.click(screen.getByRole("button", { name: "Set up the organization" }));

    expect(await screen.findByTestId("provision-message")).toHaveTextContent("Is not available.");
    expect(screen.queryByTestId("provisioned")).toBeNull();
  });

  it("shows the refusal of a role that may not set organizations up", async () => {
    fakeApi({
      "GET /api/v1/platform/plans": () => json(PLANS),
      "POST /api/v1/platform/organizations": () =>
        refusal("FORBIDDEN", "You are not allowed to perform this action.", 403),
    });
    render(<ProvisionForm />);
    await screen.findByRole("option", { name: "Plan A" });

    fill();
    fireEvent.click(screen.getByRole("button", { name: "Set up the organization" }));

    await waitFor(() => expect(screen.getByTestId("provision-message")).toHaveTextContent("not allowed"));
  });

  it("promises nothing about the account of the address", async () => {
    fakeApi({ "GET /api/v1/platform/plans": () => json(PLANS) });
    render(<ProvisionForm />);
    await screen.findByRole("option", { name: "Plan A" });

    expect(screen.getByText(/The answer is the same whether or not the address already has an account/)).toBeInTheDocument();
  });
});
