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
