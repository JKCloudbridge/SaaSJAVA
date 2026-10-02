import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "@/lib/session/SessionProvider";
import { EmailRequestForm, type EmailRequestKind } from "./EmailRequestForm";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const SENTENCE = "If you can sign up with this address, an e-mail with the next step is on its way.";

function fakeApi(answer: () => Response | Promise<Response>) {
  const calls: Array<{ key: string; body: string }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const key = `${request.method} ${new URL(request.url).pathname}`;
      if (key === "GET /api/v1/auth/csrf") {
        return new Response(null, { status: 204 });
      }
      if (key === "GET /api/v1/auth/me" || key === "POST /api/v1/auth/refresh") {
        return json({ error: { code: "UNAUTHENTICATED", message: "Authentication is required." } }, 401);
      }
      calls.push({ key, body: await request.clone().text() });
      return answer();
    }),
  );
  return calls;
}

function renderForm(kind: EmailRequestKind) {
  return render(
    <SessionProvider>
      <EmailRequestForm kind={kind} />
    </SessionProvider>,
  );
}

function submit(email: string) {
  fireEvent.change(screen.getByLabelText("Email address"), { target: { value: email } });
  fireEvent.submit(screen.getByLabelText("Email address").closest("form")!);
}

describe("EmailRequestForm", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token; path=/";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.cookie = "XSRF-TOKEN=; path=/; max-age=0";
  });

  it("sends the address to the sign-up request and shows the one sentence the API answers with", async () => {
    const calls = fakeApi(() => json({ data: { message: SENTENCE } }, 202));
    renderForm("sign-up");

    submit("person-a@example.test");

    expect(await screen.findByTestId("request-sent")).toHaveTextContent(SENTENCE);
    expect(calls).toEqual([
      { key: "POST /api/v1/auth/sign-up", body: JSON.stringify({ email: "person-a@example.test" }) },
    ]);
  });

  it("uses the reset request for the forgotten-password screen", async () => {
    const calls = fakeApi(() => json({ data: { message: "If an account exists, an e-mail is on its way." } }, 202));
    renderForm("password-reset");

    submit("person-a@example.test");

    await screen.findByTestId("request-sent");
    expect(calls[0]?.key).toBe("POST /api/v1/auth/password/forgot");
  });

  it("asks again when the person asks for the e-mail again", async () => {
    const calls = fakeApi(() => json({ data: { message: SENTENCE } }, 202));
    renderForm("sign-up");
    submit("person-a@example.test");
    await screen.findByTestId("request-sent");

    fireEvent.click(screen.getByRole("button", { name: "send it again" }));

    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it("says so for text that is not an address, and for too many requests", async () => {
    const answers = [
      () =>
        json(
          {
            error: {
              code: "VALIDATION_ERROR",
              message: "The request is invalid.",
              fields: { email: ["Is not a valid address."] },
            },
          },
          400,
        ),
      () => json({ error: { code: "RATE_LIMITED", message: "Too many requests. Try again later." } }, 429),
    ];
    fakeApi(() => answers.shift()!());
    renderForm("sign-up");

    submit("nope");
    expect(await screen.findByTestId("request-message")).toHaveTextContent("Enter a valid email address.");
    submit("person-a@example.test");

    await waitFor(() => expect(screen.getByTestId("request-message")).toHaveTextContent("Too many requests"));
  });

  it("says the server could not be reached when there is no answer", async () => {
    fakeApi(() => {
      throw new TypeError("network down");
    });
    renderForm("sign-up");

    submit("person-a@example.test");

    expect(await screen.findByTestId("request-message")).toHaveTextContent("could not be reached");
  });
});
