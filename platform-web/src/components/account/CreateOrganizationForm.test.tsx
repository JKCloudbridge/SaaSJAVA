import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { CreateOrganizationForm, suggestSlug } from "./CreateOrganizationForm";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const UNAUTHENTICATED = { error: { code: "UNAUTHENTICATED", message: "Authentication is required." } };

type Setup = { signedIn: boolean; platformHost: boolean; create?: () => Response };

function fakeApi({ signedIn, platformHost, create }: Setup) {
  const calls: Array<{ key: string; body: string }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      switch (key) {
        case "GET /api/v1/auth/csrf":
          return new Response(null, { status: 204 });
        case "GET /api/v1/auth/me":
          return signedIn
            ? json({ data: { id: "1", email: "user-a@example.test", displayName: "User A", platformRoles: [], abilities: [] } })
            : json(UNAUTHENTICATED, 401);
        case "POST /api/v1/auth/refresh":
          return json(UNAUTHENTICATED, 401);
        case "GET /api/v1/tenant/current":
          return platformHost
            ? json({ error: { code: "NOT_FOUND", message: "No organization exists at this address." } }, 404)
            : json({ data: { slug: "tenant-a", displayName: "Tenant A" } });
        case "POST /api/v1/organizations":
          calls.push({ key, body: await request.clone().text() });
          return create ? create() : json({ data: {} }, 500);
        default:
          throw new Error(`unexpected call ${key}`);
      }
    }),
  );
  return calls;
}

function renderForm() {
  return render(
    <SessionProvider>
      <CreateOrganizationForm />
    </SessionProvider>,
  );
}

async function fillAndSubmit(name: string, slug?: string) {
  fireEvent.change(await screen.findByLabelText("Organization name"), { target: { value: name } });
  if (slug !== undefined) {
    fireEvent.change(screen.getByLabelText("Short name for the web address"), { target: { value: slug } });
  }
  fireEvent.submit(screen.getByLabelText("Organization name").closest("form")!);
}

describe("suggestSlug", () => {
  it.each([
    ["Organization A", "organization-a"],
    ["  Acme & Sons, Ltd.  ", "acme-sons-ltd"],
    ["x".repeat(60), "x".repeat(40)],
  ])("suggests a short name for %s", (name, expected) => {
    expect(suggestSlug(name)).toBe(expected);
  });
});

describe("CreateOrganizationForm", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("asks a person who is not signed in to sign in first", async () => {
    fakeApi({ signedIn: false, platformHost: true });
    renderForm();

    expect(await screen.findByTestId("needs-sign-in")).toBeInTheDocument();
  });

  it("sends the name and the suggested short name and shows the address the API built", async () => {
    const calls = fakeApi({
      signedIn: true,
      platformHost: true,
      create: () =>
        json(
          { data: { slug: "organization-a", displayName: "Organization A", host: "organization-a.localhost:3000" } },
          201,
        ),
    });
    renderForm();

    await fillAndSubmit("Organization A");

    expect(await screen.findByTestId("organization-created")).toHaveTextContent("Organization A is ready");
    expect(screen.getByRole("link")).toHaveAttribute(
      "href",
      `${window.location.protocol}//organization-a.localhost:3000/sign-in`,
    );
    expect(calls[0]?.body).toBe(JSON.stringify({ displayName: "Organization A", slug: "organization-a" }));
  });

  it("keeps a short name the person typed", async () => {
    const calls = fakeApi({
      signedIn: true,
      platformHost: true,
      create: () =>
        json({ data: { slug: "mine", displayName: "Organization A", host: "mine.localhost:3000" } }, 201),
    });
    renderForm();

    await fillAndSubmit("Organization A", "mine");

    await screen.findByTestId("organization-created");
    expect(calls[0]?.body).toBe(JSON.stringify({ displayName: "Organization A", slug: "mine" }));
  });

  it("shows the API's words for a short name that is not available, and for the limit", async () => {
    const answers = [
      () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is invalid.",
              fields: { slug: ["Is not available."] },
            },
          },
          400,
        ),
      () =>
        json(
          { error: { code: "FORBIDDEN", message: "You have reached the number of organizations one person may create." } },
          403,
        ),
    ];
    fakeApi({ signedIn: true, platformHost: true, create: () => answers.shift()!() });
    renderForm();

    await fillAndSubmit("Organization A");
    await waitFor(() => expect(screen.getByTestId("organization-message")).toHaveTextContent("Is not available."));
    fireEvent.submit(screen.getByLabelText("Organization name").closest("form")!);

    await waitFor(() =>
      expect(screen.getByTestId("organization-message")).toHaveTextContent("number of organizations"),
    );
  });

  it("sends a person on an organization's address to the platform address", async () => {
    fakeApi({ signedIn: true, platformHost: false });
    renderForm();

    const notice = await screen.findByTestId("wrong-host");

    expect(notice).toHaveTextContent("platform address");
    expect(screen.getByRole("link")).toHaveAttribute("href", expect.stringContaining("/organizations/new"));
  });
});
