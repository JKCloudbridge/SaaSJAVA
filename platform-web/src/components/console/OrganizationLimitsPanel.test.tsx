import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { OrganizationLimitsPanel } from "./OrganizationLimitsPanel";

const ID = "11111111-1111-4111-8111-111111111111";
const PATH = `/api/v1/platform/organizations/${ID}/limits`;

const DEFAULTS = {
  organizationId: ID,
  overridden: false,
  organizationPerMinute: 30,
  organizationPerDay: 400,
  applicationPerMinute: 6,
  defaultOrganizationPerMinute: 30,
  defaultOrganizationPerDay: 400,
  defaultApplicationPerMinute: 6,
};

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

describe("OrganizationLimitsPanel", () => {
  it("shows what is in force beside the defaults and says whether the numbers were set", async () => {
    fakeApi({ [`GET ${PATH}`]: () => json({ data: DEFAULTS }) });
    render(<OrganizationLimitsPanel organizationId={ID} />);

    const table = await screen.findByTestId("limits-table");

    expect(table).toHaveTextContent("30");
    expect(table).toHaveTextContent("400");
    expect(screen.getByText("The defaults are in force.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Return to the defaults" })).toBeNull();
  });

  it("sets the numbers that were typed with a reason, and leaves a box that is empty out", async () => {
    const calls = fakeApi({
      [`GET ${PATH}`]: () => json({ data: DEFAULTS }),
      [`PUT ${PATH}`]: () => json({ data: { ...DEFAULTS, overridden: true, applicationPerMinute: 2 } }),
    });
    render(<OrganizationLimitsPanel organizationId={ID} />);
    await screen.findByTestId("limits-table");

    fireEvent.change(screen.getByLabelText(/each application and each person's token$/), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText(/^Reason/), { target: { value: "a customer asked" } });
    fireEvent.click(screen.getByRole("button", { name: "Set the limits" }));

    await waitFor(() => expect(calls.some((call) => call.key === `PUT ${PATH}`)).toBe(true));
    const put = calls.find((call) => call.key.startsWith("PUT"));
    expect(JSON.parse(put?.body ?? "{}")).toEqual({ applicationPerMinute: 2, reason: "a customer asked" });
    expect(await screen.findByTestId("organization-limits-notice")).toHaveTextContent("The limits were set.");
  });

  it("shows the API's words and every problem it lists when a number is refused", async () => {
    fakeApi({
      [`GET ${PATH}`]: () => json({ data: DEFAULTS }),
      [`PUT ${PATH}`]: () =>
        json({ error: { code: "VALIDATION_ERROR", message: "The request is invalid.", fields: { reason: ["Give a reason."] } } }, 400),
    });
    render(<OrganizationLimitsPanel organizationId={ID} />);
    await screen.findByTestId("limits-table");

    fireEvent.click(screen.getByRole("button", { name: "Set the limits" }));

    expect(await screen.findByTestId("organization-limits-problem")).toHaveTextContent("Give a reason.");
  });

  it("returns the numbers to the defaults with a reason, and only when they were set", async () => {
    const calls = fakeApi({
      [`GET ${PATH}`]: () => json({ data: { ...DEFAULTS, overridden: true, applicationPerMinute: 2 } }),
      [`POST ${PATH}/reset`]: () => json({ data: DEFAULTS }),
    });
    render(<OrganizationLimitsPanel organizationId={ID} />);
    await screen.findByTestId("limits-table");

    fireEvent.click(screen.getByRole("button", { name: "Return to the defaults" }));
    const form = await screen.findByRole("form", { name: "Return to the defaults" });
    fireEvent.change(within(form).getByLabelText(/Reason/), { target: { value: "no longer needed" } });
    fireEvent.click(within(form).getByRole("button", { name: "Return to the defaults" }));

    await waitFor(() => expect(calls.some((call) => call.key === `POST ${PATH}/reset`)).toBe(true));
    const post = calls.find((call) => call.key.endsWith("/reset"));
    expect(JSON.parse(post?.body ?? "{}")).toEqual({ reason: "no longer needed" });
  });

  it("shows the API's words when the limits cannot be read", async () => {
    fakeApi({ [`GET ${PATH}`]: () => refusal("FORBIDDEN", "You are not allowed to do this.", 403) });
    render(<OrganizationLimitsPanel organizationId={ID} />);

    expect(await screen.findByText("You are not allowed to do this.")).toBeInTheDocument();
  });
});
