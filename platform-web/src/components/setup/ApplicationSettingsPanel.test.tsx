import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { ApplicationSettingsPanel } from "./ApplicationSettingsPanel";

const { offers } = vi.hoisted(() => ({ offers: vi.fn(() => true) }));
vi.mock("@/lib/session/SessionProvider", () => ({ useOffers: offers }));

const limit = {
  key: "scoreLimit", label: "Score limit", description: "A score below this needs an Action plan.", kind: "PERCENT",
  value: 85, defaultValue: 85, least: 1, most: 100, changed: false, version: null as number | null,
};
const listing = (setting = limit) => ({ data: { applications: [{ application: "management-core", label: "Management Core", settings: [setting] }] } });

beforeEach(() => {
  offers.mockReturnValue(true);
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});
afterEach(() => vi.unstubAllGlobals());

describe("ApplicationSettingsPanel", () => {
  it("draws what the server lists and shows the value in force with its default", async () => {
    fakeApi({ "GET /api/v1/application-settings": () => json(listing()) });
    render(<ApplicationSettingsPanel />);
    const input = await screen.findByLabelText("Score limit (percent)");
    expect(input).toHaveValue(85);
    expect(screen.getByText(/The application starts with 85/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("sends the choice with no version for a first choice and says it applies to later reviews", async () => {
    const calls = fakeApi({
      "GET /api/v1/application-settings": () => json(listing()),
      "PUT /api/v1/application-settings": () => json(listing({ ...limit, value: 90, changed: true, version: 0 })),
    });
    render(<ApplicationSettingsPanel />);
    fireEvent.change(await screen.findByLabelText("Score limit (percent)"), { target: { value: "90" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText(/applies to reviews that end from now on/);
    const put = calls.find((call) => call.key.startsWith("PUT "))!;
    expect(JSON.parse(put.body)).toEqual({ application: "management-core", key: "scoreLimit", value: 90 });
  });

  it("writes the unit of a setting of days, takes only the server's range and says it applies at once (ADR-0266)", async () => {
    const days = {
      key: "actionPlanDueDays", label: "Action plan due after", description: "Days to send the plan.", kind: "DAYS",
      value: 10, defaultValue: 10, least: 1, most: 365, changed: false, version: null as number | null,
    };
    const calls = fakeApi({
      "GET /api/v1/application-settings": () => json(listing(days)),
      "PUT /api/v1/application-settings": () => json(listing({ ...days, value: 14, changed: true, version: 0 })),
    });
    render(<ApplicationSettingsPanel />);
    const input = await screen.findByLabelText("Action plan due after (days)");
    fireEvent.change(input, { target: { value: "366" } });
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    fireEvent.change(input, { target: { value: "14" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText(/It applies at once/);
    expect(JSON.parse(calls.find((call) => call.key.startsWith("PUT "))!.body)).toEqual({
      application: "management-core", key: "actionPlanDueDays", value: 14,
    });
  });

  it("does not offer a value outside the server's range and shows the server's refusal", async () => {
    fakeApi({
      "GET /api/v1/application-settings": () => json(listing()),
      "PUT /api/v1/application-settings": () => refusal("VALIDATION_ERROR", "Give a whole number from 1 to 100.", 422),
    });
    render(<ApplicationSettingsPanel />);
    const input = await screen.findByLabelText("Score limit (percent)");
    fireEvent.change(input, { target: { value: "150" } });
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    fireEvent.change(input, { target: { value: "70" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Give a whole number from 1 to 100.");
  });

  it("takes a chosen value back with the version read", async () => {
    const chosen = { ...limit, value: 90, changed: true, version: 3 };
    const calls = fakeApi({
      "GET /api/v1/application-settings": () => json(listing(chosen)),
      "DELETE /api/v1/application-settings": () => json(listing()),
    });
    render(<ApplicationSettingsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "Use the application's value" }));
    await waitFor(() => expect(calls.some((call) => call.key.startsWith("DELETE "))).toBe(true));
  });

  it("says so when the person has no access, and calls nothing", () => {
    offers.mockReturnValue(false);
    const calls = fakeApi({});
    render(<ApplicationSettingsPanel />);
    expect(screen.getByRole("status")).toHaveTextContent("You do not have access to application settings.");
    expect(calls).toHaveLength(0);
  });
});
