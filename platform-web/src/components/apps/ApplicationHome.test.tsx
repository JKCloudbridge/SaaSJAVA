import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FIELD, SALES, shellApi, ShellProviders } from "@/test/shellApi";
import { ApplicationHome } from "./ApplicationHome";

let params: { application: string };
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useParams: () => params,
  useRouter: () => ({ replace, push: vi.fn() }),
  usePathname: () => "/",
}));

beforeEach(() => {
  params = { application: "Field__c" };
  replace.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderHome() {
  return render(
    <ShellProviders>
      <ApplicationHome />
    </ShellProviders>,
  );
}

describe("opening an application", () => {
  it("opens it on its first page, in the order the API lists", async () => {
    shellApi();
    renderHome();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Field__c/c-Inspections__c"));
  });

  it("opens on the first page that is a page of its own, not on a heading or a link", async () => {
    shellApi({ applications: [{ ...FIELD, items: [FIELD.items[0]!, FIELD.items[3]!, FIELD.items[2]!] }] });
    renderHome();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/apps/Field__c/o-Deal__c"));
  });

  it("says an application has no pages yet, and offers Setup to who may go there", async () => {
    shellApi({ applications: [{ ...SALES, apiName: "Field__c", label: "Field Service", items: [] }], abilities: ["metadata.view"] });
    renderHome();

    const note = await screen.findByTestId("application-empty");

    expect(note).toHaveTextContent("This application has no pages yet.");
    expect(screen.getByRole("link", { name: "Setup" })).toHaveAttribute("href", "/setup/applications");
    expect(replace).not.toHaveBeenCalled();
  });

  it("tells a member without the ability to ask an administrator", async () => {
    shellApi({ applications: [{ ...SALES, apiName: "Field__c", items: [] }] });
    renderHome();

    expect(await screen.findByText("Ask an administrator to add pages to it.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Setup" })).toBeNull();
  });

  it("says an application is not available when the member does not have it", async () => {
    params = { application: "Gone__c" };
    shellApi();
    renderHome();

    expect(await screen.findByText("This application is not available.")).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
