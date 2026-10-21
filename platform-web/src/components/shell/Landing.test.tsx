import { render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as navigation from "@/lib/navigation";
import { json, refusal, type Call } from "@/test/fakeApi";
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
let navigate: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  replace.mockReset();
  navigate = vi.spyOn(navigation, "navigate").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.history.replaceState({}, "", "/");
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

const ORG_A = { slug: "org-a", displayName: "Organization A", host: "org-a.localhost:3000" };
const ORG_B = { slug: "org-b", displayName: "Organization B", host: "org-b.localhost:3000" };
const PROOF = "proof-01234567890123456789";

function person(platformRoles: string[] = []) {
  return () =>
    json({
      data: { id: "p-1", email: "user-a@example.test", displayName: "User A", platformRoles, abilities: [] },
    });
}

/** The platform's address with a signed-in person who belongs to the given organizations. */
function platformApi(options: {
  organizations?: unknown[] | (() => Response);
  platformRoles?: string[];
  switchAnswer?: () => Response;
}) {
  const organizations = options.organizations ?? [];
  return shellApi({
    platformHost: true,
    more: {
      "GET /api/v1/auth/me": person(options.platformRoles),
      "GET /api/v1/organizations": () =>
        typeof organizations === "function" ? organizations() : json({ data: organizations }),
      "POST /api/v1/auth/switch": options.switchAnswer ?? (() => json({ data: { host: ORG_A.host, token: PROOF } })),
    },
  });
}

function afterSigningIn() {
  window.history.replaceState({}, "", "/?signedIn=1");
}

function handoffRequests(calls: Call[]) {
  return calls.filter((call) => call.key === "POST /api/v1/auth/switch");
}

describe("the platform's address, right after signing in", () => {
  it("opens the only organization through the one-time handoff, never showing the welcome page", async () => {
    const calls = platformApi({ organizations: [ORG_A] });
    afterSigningIn();
    renderLanding();

    expect(await screen.findByText("Opening your organization…")).toBeInTheDocument();
    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith(`${window.location.protocol}//${ORG_A.host}/switch#token=${PROOF}`),
    );
    expect(handoffRequests(calls)).toHaveLength(1);
    expect(handoffRequests(calls)[0]?.body).toBe(JSON.stringify({ slug: "org-a" }));
    expect(screen.queryByRole("heading", { name: /Welcome/ })).toBeNull();
    expect(window.location.search).toBe("");
  });

  it("asks for the handoff once under StrictMode, because the proof is single use", async () => {
    const calls = platformApi({ organizations: [ORG_A] });
    afterSigningIn();
    renderLanding(true);

    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(handoffRequests(calls)).toHaveLength(1);
  });

  it("lists several organizations to choose from and enters the one that is pressed", async () => {
    const calls = platformApi({
      organizations: [ORG_A, ORG_B],
      switchAnswer: () => json({ data: { host: ORG_B.host, token: PROOF } }),
    });
    afterSigningIn();
    renderLanding();

    expect(await screen.findByRole("heading", { name: "Choose your organization" })).toBeInTheDocument();
    expect(handoffRequests(calls)).toHaveLength(0);
    screen.getByRole("button", { name: "Organization B" }).click();

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith(`${window.location.protocol}//${ORG_B.host}/switch#token=${PROOF}`),
    );
    expect(handoffRequests(calls)[0]?.body).toBe(JSON.stringify({ slug: "org-b" }));
  });

  it("says plainly that a person with no organization is not a member of one yet", async () => {
    platformApi({ organizations: [] });
    afterSigningIn();
    renderLanding();

    expect(await screen.findByTestId("no-organization")).toHaveTextContent(
      "You are not a member of an organization yet.",
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it("sends a person with a platform role to the console, even when they belong to organizations", async () => {
    const calls = platformApi({ organizations: [ORG_A], platformRoles: ["PLATFORM_ADMIN"] });
    afterSigningIn();
    renderLanding();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/console"));
    expect(navigate).not.toHaveBeenCalled();
    expect(calls.some((call) => call.key === "GET /api/v1/organizations")).toBe(false);
  });

  it("shows the API's words when the handoff is refused, keeps the list, and does not try again by itself", async () => {
    const calls = platformApi({
      organizations: [ORG_A],
      switchAnswer: () => refusal("NOT_FOUND", "That organization is not available.", 404),
    });
    afterSigningIn();
    renderLanding();

    expect(await screen.findByTestId("enter-failed")).toHaveTextContent("That organization is not available.");
    expect(screen.getByRole("heading", { name: "Choose your organization" })).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(handoffRequests(calls)).toHaveLength(1);
  });

  it("says so, and offers to try again, when the list of organizations could not be had", async () => {
    let healthy = false;
    platformApi({
      organizations: () =>
        healthy ? json({ data: [ORG_A, ORG_B] }) : refusal("SERVICE_UNAVAILABLE", "Try again later.", 503),
    });
    afterSigningIn();
    renderLanding();

    expect(await screen.findByTestId("organizations-failed")).toHaveTextContent("Try again later.");
    expect(navigate).not.toHaveBeenCalled();

    healthy = true;
    screen.getByRole("button", { name: "Try again" }).click();
    expect(await screen.findByRole("heading", { name: "Choose your organization" })).toBeInTheDocument();
  });
});

describe("the platform's address on a deliberate visit", () => {
  it("does not bounce a person with one organization back in: the list is shown instead", async () => {
    const calls = platformApi({ organizations: [ORG_A] });
    renderLanding();

    expect(await screen.findByRole("heading", { name: "Choose your organization" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Organization A" })).toBeInTheDocument();
    expect(handoffRequests(calls)).toHaveLength(0);
    expect(navigate).not.toHaveBeenCalled();
  });

  it("does not send a person with a platform role to the console, and offers the link", async () => {
    platformApi({ organizations: [ORG_A, ORG_B], platformRoles: ["PLATFORM_SUPPORT"] });
    renderLanding();

    expect(await screen.findByRole("link", { name: "Platform console" })).toHaveAttribute("href", "/console");
    expect(replace).not.toHaveBeenCalled();
  });

  it("offers no console to a person without a platform role", async () => {
    platformApi({ organizations: [ORG_A, ORG_B] });
    renderLanding();

    await screen.findByRole("heading", { name: "Choose your organization" });
    expect(screen.queryByRole("link", { name: "Platform console" })).toBeNull();
  });

  it("offers no 'Create an organization' on the page: it is in the person's menu", async () => {
    platformApi({ organizations: [] });
    renderLanding();

    await screen.findByTestId("no-organization");
    expect(screen.queryByRole("link", { name: "Create an organization" })).toBeNull();
  });
});

describe("the start page of a visitor who is not signed in", () => {
  function signedOutApi() {
    shellApi({
      platformHost: true,
      more: {
        "GET /api/v1/auth/me": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
        "POST /api/v1/auth/refresh": () => refusal("UNAUTHENTICATED", "Authentication is required.", 401),
      },
    });
  }

  it("shows the sign-in form right here, with no extra click to a sign-in page", async () => {
    signedOutApi();
    renderLanding();

    expect(await screen.findByRole("heading", { name: "Welcome to Platform" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email address")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute(
      "href",
      expect.stringContaining("/sign-up"),
    );
    expect(screen.queryByText(/web frontend/)).toBeNull();
    expect(replace).not.toHaveBeenCalled();
  });

  it("enters nothing when the marker is present but the session is gone", async () => {
    signedOutApi();
    afterSigningIn();
    renderLanding();

    expect(await screen.findByRole("heading", { name: "Welcome to Platform" })).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
    expect(window.location.search).toBe("");
  });
});

describe("when the platform cannot be reached", () => {
  it("says so and offers to try again, instead of a welcome page with no way in", async () => {
    shellApi({
      platformHost: true,
      more: { "GET /api/v1/auth/me": () => refusal("INTERNAL_ERROR", "Something went wrong.", 500) },
    });
    renderLanding();

    expect(await screen.findByTestId("platform-unreachable")).toHaveTextContent("The platform could not be reached.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});

describe("a member of an organization who arrives with the marker", () => {
  it("lands as usual, and the marker is taken out of the address", async () => {
    shellApi({ applications: [FIELD] });
    afterSigningIn();
    renderLanding();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Field__c"));
    expect(window.location.search).toBe("");
  });
});
