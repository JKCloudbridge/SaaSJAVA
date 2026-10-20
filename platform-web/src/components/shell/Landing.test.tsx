import { render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { json, refusal } from "@/test/fakeApi";
import { FIELD, SALES, shellApi, ShellProviders } from "@/test/shellApi";
import { Landing } from "./Landing";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  usePathname: () => "/",
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
// The status card asks the platform API for its own status; the landing does not depend on it.
vi.mock("@/components/PlatformStatusCard", () => ({ PlatformStatusCard: () => <p>status card</p> }));

beforeEach(() => {
  replace.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderLanding(strict = false) {
  const tree = (
    <ShellProviders>
      <Landing />
    </ShellProviders>
  );
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree);
}

describe("where a member lands", () => {
  it("opens the first application the API lists, in the order it lists them", async () => {
    shellApi({ applications: [FIELD, SALES], abilities: [] });
    renderLanding();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Field__c"));
  });

  it("opens the first application for a person who may set things up too: settings are one click away", async () => {
    shellApi({ applications: [SALES, FIELD], abilities: ["metadata.view", "members.view"] });
    renderLanding();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Sales__c"));
  });

  it("lands on General when there is no application and the person may set things up", async () => {
    shellApi({ applications: [], abilities: ["audit.view"] });
    renderLanding();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/setup/general"));
  });

  it("stays on a plain page saying no application is available when there is none and nothing to set up", async () => {
    shellApi({ applications: [], abilities: ["views.share"] });
    renderLanding();

    expect(await screen.findByTestId("no-application")).toHaveTextContent("No application is available to you yet.");
    expect(replace).not.toHaveBeenCalled();
  });

  it("decides nothing while the list of applications is on its way, and does not flash the wrong page", async () => {
    let answer: (() => void) | undefined;
    shellApi({
      applications: [FIELD],
      applicationsAnswer: () =>
        new Promise<Response>((resolve) => {
          answer = () => resolve(json({ data: [FIELD] }));
        }),
    });
    renderLanding();

    // The list is asked for once the sign-in is known; until it answers there is nothing to decide on.
    await waitFor(() => expect(answer).toBeDefined());
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByTestId("no-application")).toBeNull();
    expect(replace).not.toHaveBeenCalled();

    answer?.();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Field__c"));
  });

  it("says so, and offers to try again, when the list could not be had; it never lands on a guess", async () => {
    let healthy = false;
    shellApi({
      abilities: ["metadata.view"],
      applicationsAnswer: () =>
        healthy ? json({ data: [SALES] }) : refusal("SERVICE_UNAVAILABLE", "Try again later.", 503),
    });
    renderLanding();

    const failed = await screen.findByTestId("landing-failed");
    expect(failed).toHaveTextContent("Your applications could not be loaded.");
    expect(replace).not.toHaveBeenCalled();

    healthy = true;
    screen.getByRole("button", { name: "Try again" }).click();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Sales__c"));
  });

  it("does the same under StrictMode, which runs effects twice in development", async () => {
    shellApi({ applications: [FIELD] });
    renderLanding(true);

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Field__c"));
    expect(replace.mock.calls.every(([address]) => address === "/apps/Field__c")).toBe(true);
  });
});

describe("the start page of everybody else", () => {
  it("welcomes a person on the platform's address, with the console for a platform role", async () => {
    shellApi({
      platformHost: true,
      more: {
        "GET /api/v1/auth/me": () =>
          json({
            data: {
              id: "p-1",
              email: "platform-a@example.test",
              displayName: "Platform A",
              platformRoles: ["PLATFORM_ADMIN"],
              abilities: [],
            },
          }),
      },
    });
    renderLanding();

    expect(await screen.findByRole("link", { name: "Platform console" })).toHaveAttribute("href", "/console");
    expect(screen.getByRole("link", { name: "Create an organization" })).toHaveAttribute("href", "/organizations/new");
    expect(screen.getByRole("heading", { name: "Welcome" })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("offers no console to a person on the platform's address without a platform role", async () => {
    shellApi({ platformHost: true });
    renderLanding();

    expect(await screen.findByRole("link", { name: "Create an organization" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Platform console" })).toBeNull();
  });

  it("welcomes nobody-signed-in without sending them anywhere", async () => {
    shellApi({
      more: {
        "GET /api/v1/auth/me": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
        "POST /api/v1/auth/refresh": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
      },
    });
    renderLanding();

    expect(await screen.findByRole("heading", { name: "Welcome" })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
