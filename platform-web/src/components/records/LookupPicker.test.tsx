import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal, type Call } from "@/test/fakeApi";
import { field } from "@/test/records";
import { LookupPicker } from "./LookupPicker";

const OPTIONS = "POST /api/v1/objects/Deal__c/lookups/account__c/options";
const ACCOUNT = field("account__c", "LOOKUP", { label: "Account", targetObject: "Account__c", targetLabel: "Account" });

afterEach(() => {
  vi.unstubAllGlobals();
});

function pick(value = "", label?: string, onChange = vi.fn()) {
  render(<LookupPicker objectApiName="Deal__c" field={ACCOUNT} id="f" value={value} label={label} onChange={onChange} />);
  return onChange;
}

function asked(calls: Call[]): string[] {
  return calls.filter((call) => call.key === OPTIONS).map((call) => call.body);
}

describe("choosing the record a link points to", () => {
  it("offers what the API finds when the person starts to look, and reports the one chosen with its name", async () => {
    fakeApi({ [OPTIONS]: () => json({ data: [{ id: "a1", label: "Acme" }, { id: "a2", label: "Acorn" }] }) });
    const onChange = pick();

    fireEvent.focus(screen.getByRole("textbox"));
    fireEvent.click(await screen.findByRole("button", { name: "Acorn" }));

    expect(onChange).toHaveBeenCalledWith("a2", "Acorn");
  });

  it("asks with the text typed, in the body and never in the address, a moment after the person stops", async () => {
    const calls = fakeApi({ [OPTIONS]: () => json({ data: [] }) });
    pick();

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "ac" } });
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "acm" } });

    await waitFor(() => expect(asked(calls)).toEqual(['{"text":"acm","limit":10}']));
    expect(calls.map((call) => call.key).filter((key) => key.includes("?"))).toEqual([]);
  });

  it("says when nothing matches", async () => {
    fakeApi({ [OPTIONS]: () => json({ data: [] }) });
    pick();

    fireEvent.focus(screen.getByRole("textbox"));

    expect(await screen.findByText("Nothing matches.")).toBeInTheDocument();
  });

  it("shows the API's words when the search is refused (for example a text that is too common)", async () => {
    fakeApi({ [OPTIONS]: () => refusal("VALIDATION_ERROR", "Type more letters.", 422) });
    pick();

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "a" } });

    expect(await screen.findByRole("alert", {}, { timeout: 2000 })).toHaveTextContent("Type more letters.");
  });

  it("shows the record chosen by name, with a way to change it", () => {
    const onChange = pick("a1", "Acme");

    expect(screen.getByText("Acme")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Change" }));

    expect(onChange).toHaveBeenCalledWith("", "");
  });

  it("never shows an identifier when the name is not known", () => {
    pick("11111111-1111-1111-1111-111111111111");

    expect(screen.getByText("Linked record")).toBeInTheDocument();
    expect(screen.queryByText("11111111-1111-1111-1111-111111111111")).toBeNull();
  });

  it("does not ask anything until the person looks", () => {
    const calls = fakeApi({ [OPTIONS]: () => json({ data: [] }) });
    pick();

    expect(asked(calls)).toEqual([]);
  });
});
