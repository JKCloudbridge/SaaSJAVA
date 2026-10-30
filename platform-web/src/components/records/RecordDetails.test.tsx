import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json } from "@/test/fakeApi";
import { DEAL_FORM, deal } from "@/test/records";
import { RecordDetails } from "./RecordDetails";

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

const OWNER = "11111111-1111-1111-1111-111111111111";

let calls: ReturnType<typeof fakeApi> = [];

// The owner's name is asked for after the first draw. A test that ends before the answer would let it arrive after the
// fake API is gone, and the reply then lands on a worker that is closing.
afterEach(async () => {
  await waitFor(() => expect(calls.some((one) => one.key.endsWith("/labels"))).toBe(true));
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
  vi.unstubAllGlobals();
});

function renderDetails(values: Record<string, unknown> = {}) {
  calls = fakeApi({ "POST /api/v1/objects/Deal__c/lookups/ownerId/labels": () => json({ data: { labels: { [OWNER]: "User A" } } }) });
  return render(
    <RecordDetails objectApiName="Deal__c" form={DEAL_FORM} record={deal("r1", values)} recordHref={(o, id) => `/records/${o}/${id}`} />,
  );
}

describe("the details of a record, read", () => {
  it("lays the values out in the sections of the layout, in its order", () => {
    renderDetails({ name__c: "Acme" });

    expect(screen.getByRole("heading", { name: "Deal" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "More" })).toBeInTheDocument();
    expect(screen.getByTestId("value-name__c")).toHaveTextContent("Acme");
  });

  it("writes a dash for a field with no value, and marks a required field", () => {
    renderDetails({ closes__c: null });

    expect(screen.getByTestId("value-closes__c")).toHaveTextContent("—");
    expect(screen.getByText("Name").parentElement).toHaveTextContent("Name *");
  });

  it("names the linked owner, never shows the identifier", async () => {
    renderDetails();

    expect(await screen.findByText("User A")).toBeInTheDocument();
    expect(screen.queryByText(OWNER)).toBeNull();
  });

  it("draws no input: it is for reading", () => {
    renderDetails();

    expect(screen.queryByRole("textbox")).toBeNull();
  });
});
