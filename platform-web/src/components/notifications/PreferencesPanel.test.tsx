import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { PreferencesPanel } from "./PreferencesPanel";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=test-token; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
});

const GET = "GET /api/v1/notification-preferences";
const PUT = "PUT /api/v1/notification-preferences";

function choice(category: string, channel: string, extra: Record<string, unknown> = {}) {
  return {
    category,
    categoryLabel: `${category} words`,
    description: `${category} covers things.`,
    channel,
    channelLabel: channel === "MAIL" ? "By mail" : "In the application",
    enabled: true,
    locked: false,
    ...extra,
  };
}

const CHOICES = [
  choice("TASKS", "IN_APP"),
  choice("TASKS", "MAIL"),
  choice("STANDING", "IN_APP", { locked: true }),
  choice("STANDING", "MAIL", { locked: true }),
];

describe("the notification preferences", () => {
  it("draws every switch the API lists, grouped by its category, with the fixed ones on and disabled", async () => {
    fakeApi({ [GET]: () => json({ data: { choices: CHOICES } }) });

    render(<PreferencesPanel />);

    expect(await screen.findByText("TASKS words")).toBeInTheDocument();
    expect(screen.getByText("STANDING words")).toBeInTheDocument();
    const boxes = screen.getAllByRole("checkbox");
    expect(boxes).toHaveLength(4);
    expect(boxes[0]).toBeEnabled();
    expect(boxes[2]).toBeChecked();
    expect(boxes[2]).toBeDisabled();
    expect(screen.getAllByText("(always on)")).toHaveLength(2);
  });

  it("sends only the switches that were changed and shows the answer", async () => {
    let sent = "";
    fakeApi({
      [GET]: () => json({ data: { choices: CHOICES } }),
      [PUT]: async (request) => {
        sent = await request.text();
        return json({ data: { choices: [choice("TASKS", "IN_APP", { enabled: false }), ...CHOICES.slice(1)] } });
      },
    });

    render(<PreferencesPanel />);
    const boxes = await screen.findAllByRole("checkbox");
    fireEvent.click(boxes[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("preferences-notice")).toHaveTextContent("saved");
    expect(JSON.parse(sent)).toEqual({ choices: [{ category: "TASKS", channel: "IN_APP", enabled: false }] });
    expect(screen.getAllByRole("checkbox")[0]).not.toBeChecked();
  });

  it("sends nothing when nothing was changed", async () => {
    const calls = fakeApi({ [GET]: () => json({ data: { choices: CHOICES } }) });

    render(<PreferencesPanel />);
    await screen.findAllByRole("checkbox");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("preferences-notice")).toHaveTextContent("Nothing to save");
    expect(calls.some((one) => one.key === PUT)).toBe(false);
  });

  it("shows the API's words when it refuses", async () => {
    fakeApi({
      [GET]: () => json({ data: { choices: CHOICES } }),
      [PUT]: () => refusal("VALIDATION_ERROR", "This always reaches you; it cannot be switched off.", 400),
    });

    render(<PreferencesPanel />);
    fireEvent.click((await screen.findAllByRole("checkbox"))[1]!);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByTestId("preferences-problem")).toHaveTextContent("cannot be switched off");
  });
});
