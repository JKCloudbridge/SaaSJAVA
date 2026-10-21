import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { Author, useAuthorship } from "./authorship";

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = "GET /api/v1/metadata/objects/Employee__c/authorship";

const ENTRY = {
  kind: "FIELD",
  apiName: "code__c",
  createdBy: "Member A",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-02T10:00:00Z",
};

function Show({ platformDefined = false, which = "created" as "created" | "updated", name = "code__c" }) {
  const authorship = useAuthorship("Employee__c", 0);
  return (
    <p data-testid="author">
      <Author authorship={authorship} kind="FIELD" apiName={name} which={which} platformDefined={platformDefined} />
    </p>
  );
}

describe("who made and who last changed a definition", () => {
  it("shows the name and the date once the API has answered", async () => {
    fakeApi({ [PATH]: () => json({ data: [ENTRY] }) });
    render(<Show />);

    await vi.waitFor(() => expect(screen.getByTestId("author")).toHaveTextContent("Member A"));
    expect(screen.getByTestId("author").querySelector("time")).toHaveAttribute("datetime", "2026-10-01T10:00:00Z");
  });

  it("shows a dash for a person who is no longer a member, and never an identifier", async () => {
    fakeApi({ [PATH]: () => json({ data: [ENTRY] }) });
    render(<Show which="updated" />);

    await vi.waitFor(() => expect(screen.getByTestId("author").querySelector("time")).not.toBeNull());
    expect(screen.getByTestId("author")).toHaveTextContent("—");
  });

  it("says Platform for a definition the platform made, which has no entry", async () => {
    fakeApi({ [PATH]: () => json({ data: [] }) });
    render(<Show platformDefined name="id" />);

    await vi.waitFor(() => expect(screen.getByTestId("author")).toHaveTextContent("Platform"));
  });

  it("shows a dash while the answer is on the way or when it cannot be read, and never fails the screen", async () => {
    fakeApi({ [PATH]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });
    render(<Show />);

    expect(screen.getByTestId("author")).toHaveTextContent("—");
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByTestId("author")).toHaveTextContent("—");
  });
});
