import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeRouter, setUrl } from "@/test/navigation";
import { fakeApi, json, type Call } from "@/test/fakeApi";
import { chooseView, forgetRememberedView, rememberView, storedView, viewFromAddress } from "./homeView";
import { ManagementHomePage } from "./ManagementHome";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);
vi.mock("../application", () => ({ useApplication: () => "management-core", useApplicationLabel: () => "Management Core" }));

const BOARD = {
  generatedAt: "2026-11-20T09:30:00Z",
  month: "2026-11",
  today: "2026-11-20",
  months: [],
  monthChoices: ["2026-11"],
  filters: { plants: [], forms: [] },
  applied: {},
  atLeast: false,
  plants: [],
  grid: [],
  plans: [],
  totals: { plansSent: 0, plansSubmitted: 0, overdue: 0 },
  problems: [],
};

const SUMMARY = {
  generatedAt: "2026-11-20T09:30:00Z",
  month: "2026-11",
  previousMonth: "2026-10",
  months: [],
  monthChoices: ["2026-11"],
  filters: { plants: [], forms: [] },
  applied: {},
  legend: {},
  tiles: {},
  charts: {},
  problems: [],
};

let calls: Call[];

function open(address: string) {
  calls = fakeApi({
    "POST /api/v1/management-home/plant-board": () => json({ data: BOARD }),
    "POST /api/v1/management-home/summary": () => json({ data: SUMMARY }),
  });
  setUrl(address);
  return render(<ManagementHomePage />);
}

const asked = (path: string) => calls.filter((call) => call.key === `POST /api/v1/management-home/${path}`).length;

beforeEach(() => {
  window.localStorage.clear();
  forgetRememberedView();
  fakeRouter.push.mockClear();
  fakeRouter.replace.mockClear();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("the choice between the two layouts of the Management Core home", () => {
  it("draws Option 1 by default and asks only for the plant board", async () => {
    open("/apps/management-core/s-home");

    expect(await screen.findByTestId("management-board")).toBeInTheDocument();
    expect(screen.queryByTestId("management-home")).toBeNull();
    expect(asked("plant-board")).toBe(1);
    expect(asked("summary")).toBe(0);
  });

  it("offers exactly two choices, Option 1 and Option 2, and marks the one in use", async () => {
    open("/apps/management-core/s-home");
    await screen.findByTestId("management-board");

    const group = screen.getByTestId("view-switch");
    const buttons = Array.from(group.querySelectorAll("button"));
    expect(buttons.map((button) => button.textContent)).toEqual(["Option 1", "Option 2"]);
    expect(buttons[0]).toHaveAttribute("aria-pressed", "true");
    expect(buttons[1]).toHaveAttribute("aria-pressed", "false");
  });

  it("draws Option 2, and asks only for its numbers, when the address says view=2", async () => {
    open("/apps/management-core/s-home?view=2");

    expect(await screen.findByTestId("management-home")).toBeInTheDocument();
    expect(asked("summary")).toBe(1);
    expect(asked("plant-board")).toBe(0);
  });

  it("lets the address win over what the browser remembers", async () => {
    window.localStorage.setItem("management-home-view", "2");
    open("/apps/management-core/s-home?view=1");

    expect(await screen.findByTestId("management-board")).toBeInTheDocument();
    expect(asked("summary")).toBe(0);
  });

  it("uses what the browser remembers when the address names nothing", async () => {
    window.localStorage.setItem("management-home-view", "2");
    open("/apps/management-core/s-home");

    expect(await screen.findByTestId("management-home")).toBeInTheDocument();
  });

  it("remembers a click for the next visit and takes a view out of the address so the click counts", async () => {
    open("/apps/management-core/s-home?view=1&plant=P1");
    await screen.findByTestId("management-board");

    fireEvent.click(screen.getByRole("button", { name: "Option 2" }));

    expect(await screen.findByTestId("management-home")).toBeInTheDocument();
    expect(window.localStorage.getItem("management-home-view")).toBe("2");
    await waitFor(() => expect(fakeRouter.replace).toHaveBeenCalledWith("/apps/management-core/s-home?plant=P1", { scroll: false }));
  });

  it("works when the browser refuses its storage: the default is used and a click lasts as long as the page", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    open("/apps/management-core/s-home");

    expect(await screen.findByTestId("management-board")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Option 2" }));
    expect(await screen.findByTestId("management-home")).toBeInTheDocument();
  });

  it("holds no permission and nothing on the server: choosing a layout calls no endpoint of its own", async () => {
    open("/apps/management-core/s-home");
    await screen.findByTestId("management-board");

    fireEvent.click(screen.getByRole("button", { name: "Option 2" }));
    await screen.findByTestId("management-home");

    expect(calls.every((call) => call.key.includes("/management-home/"))).toBe(true);
  });
});

describe("the choice, as a rule", () => {
  it("takes the address first, then the browser, then Option 1; a value that is neither is no choice", () => {
    expect(viewFromAddress("1")).toBe("1");
    expect(viewFromAddress("2")).toBe("2");
    expect(viewFromAddress("3")).toBeUndefined();
    expect(viewFromAddress(null)).toBeUndefined();
    expect(chooseView("2", "1")).toBe("2");
    expect(chooseView(undefined, "2")).toBe("2");
    expect(chooseView(undefined, null)).toBe("1");
  });

  it("reads a stored value only when it is one of the two", () => {
    window.localStorage.setItem("management-home-view", "9");
    expect(storedView()).toBeNull();
    rememberView("2");
    expect(storedView()).toBe("2");
  });
});
