import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { WorkingSetBar } from "./WorkingSetBar";

const page = vi.hoisted(() => ({ section: "" }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(page.section ? `section=${page.section}` : ""),
}));

beforeEach(() => {
  window.sessionStorage.clear();
  page.section = "";
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
});

const SETS = {
  data: [
    { id: "set-1", name: "Projects", status: "DRAFT", changeCount: 0, changes: [] },
    { id: "set-0", name: "Earlier", status: "PUBLISHED", changeCount: 1, changes: [] },
  ],
};

describe("WorkingSetBar", () => {
  it("offers live at once and each open change set, not the published ones", async () => {
    fakeApi({ "GET /api/v1/metadata/change-sets": () => json(SETS) });

    render(<WorkingSetBar />);

    const select = await screen.findByLabelText("Changes on the object pages go");
    const options = Array.from(select.querySelectorAll("option")).map((option) => option.textContent);
    expect(options).toEqual(["live at once", "into the change set “Projects”"]);
    expect(screen.getByText(/goes live at once|Each change goes live at once/)).toBeInTheDocument();
  });

  it("remembers the choice for the tab and says nothing is live until the set is published", async () => {
    fakeApi({ "GET /api/v1/metadata/change-sets": () => json(SETS) });
    render(<WorkingSetBar />);
    const select = await screen.findByLabelText("Changes on the object pages go");

    fireEvent.change(select, { target: { value: "set-1" } });

    expect(window.sessionStorage.getItem("platform.workingChangeSet")).toBe("set-1");
    expect(await screen.findByText(/Nothing you change is live until the change set is published/)).toBeInTheDocument();
  });

  it("forgets a choice whose change set is no longer open", async () => {
    window.sessionStorage.setItem("platform.workingChangeSet", "set-0");
    fakeApi({ "GET /api/v1/metadata/change-sets": () => json(SETS) });

    render(<WorkingSetBar />);

    await vi.waitFor(() => expect(window.sessionStorage.getItem("platform.workingChangeSet")).toBeNull());
  });

  it("is not shown on the Sharing section, whose changes are live at once and are not part of a change set", async () => {
    page.section = "sharing";
    const calls = fakeApi({ "GET /api/v1/metadata/change-sets": () => json(SETS) });

    render(<WorkingSetBar />);

    await vi.waitFor(() => expect(calls.some((call) => call.key === "GET /api/v1/metadata/change-sets")).toBe(true));
    expect(screen.queryByTestId("working-set")).toBeNull();
  });

  it("stays quiet when there is nothing to choose or the list cannot be read", async () => {
    fakeApi({ "GET /api/v1/metadata/change-sets": () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });

    render(<WorkingSetBar />);

    await vi.waitFor(() => expect(screen.queryByTestId("working-set")).toBeNull());
  });
});
