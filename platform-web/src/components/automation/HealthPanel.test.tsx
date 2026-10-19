import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { HealthPanel } from "./HealthPanel";

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

const ORGANIZATION = "GET /api/v1/automation/health";
const PLATFORM = "GET /api/v1/platform/automation/health";

function health(attention: boolean) {
  return {
    data: {
      generatedAt: "2026-10-18T12:00:00Z",
      sections: [
        {
          key: "one",
          title: "The first part",
          items: [
            { key: "one.waiting", label: "Things waiting", count: 4, needsAttention: false, href: "/somewhere" },
            { key: "one.failed", label: "Things that failed", count: attention ? 2 : 0, needsAttention: attention },
          ],
        },
        { key: "two", title: "The second part", items: [{ key: "two.x", label: "Another count", count: 0, needsAttention: false }] },
      ],
    },
  };
}

describe("the health of automation", () => {
  it("draws the sections the API gives, with the counts and where to look", async () => {
    fakeApi({ [ORGANIZATION]: () => json(health(true)) });

    render(<HealthPanel scope="organization" />);

    const first = await screen.findByTestId("health-one");
    expect(first).toHaveTextContent("The first part");
    expect(within(first).getByRole("link", { name: "Things waiting" })).toHaveAttribute("href", "/somewhere");
    expect(within(first).getByText("Things that failed")).toBeInTheDocument();
    expect(screen.getByTestId("health-two")).toHaveTextContent("The second part");
    expect(screen.getByTestId("health-summary")).toHaveTextContent("1 thing needs attention.");
  });

  it("says plainly when nothing needs attention", async () => {
    fakeApi({ [ORGANIZATION]: () => json(health(false)) });

    render(<HealthPanel scope="organization" />);

    expect(await screen.findByTestId("health-summary")).toHaveTextContent("Nothing needs attention.");
  });

  it("asks the platform's address for the console and reads it again on request", async () => {
    let answer = health(false);
    const calls = fakeApi({ [PLATFORM]: () => json(answer) });

    render(<HealthPanel scope="platform" />);
    expect(await screen.findByTestId("health-summary")).toHaveTextContent("Nothing needs attention.");

    answer = health(true);
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    expect(await screen.findByText(/1 thing needs attention/)).toBeInTheDocument();
    expect(calls.filter((one) => one.key === PLATFORM)).toHaveLength(2);
    expect(calls.some((one) => one.key === ORGANIZATION)).toBe(false);
  });

  it("shows the API's words when it refuses", async () => {
    fakeApi({ [ORGANIZATION]: () => refusal("FORBIDDEN", "You may not see this.", 403) });

    render(<HealthPanel scope="organization" />);

    expect(await screen.findByTestId("health-failed")).toHaveTextContent("You may not see this.");
  });
});
