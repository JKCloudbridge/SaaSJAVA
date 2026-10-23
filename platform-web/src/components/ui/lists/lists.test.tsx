import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { currentUrl, setUrl } from "@/test/navigation";
import { Breadcrumb } from "./Breadcrumb";
import { DetailGrid, DetailItem, DetailPage, DetailSection } from "./DetailPage";
import { ListPage } from "./ListPage";
import { Tag } from "./Tag";
import { useListRoute, type ListRouteOptions } from "./useListRoute";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

interface Row {
  id: string;
  name: string;
  kind: string;
}

const ROWS: Row[] = [
  { id: "1", name: "Alpha", kind: "System" },
  { id: "2", name: "Beta", kind: "Custom" },
  { id: "3", name: "Gamma", kind: "Custom" },
];

function list(overrides: Partial<Parameters<typeof ListPage<Row>>[0]> = {}) {
  return (
    <ListPage<Row>
      title="Things"
      items={ROWS}
      rowKey={(row) => row.id}
      primaryHeader="Name"
      primary={(row) => row.name}
      columns={[{ key: "kind", header: "Kind", kind: "tag", cell: (row) => <Tag>{row.kind}</Tag> }]}
      searchText={(row) => `${row.name} ${row.kind}`}
      emptyText="There are no things yet."
      testId="things"
      rowTestId="thing-row"
      {...overrides}
    />
  );
}

describe("ListPage", () => {
  it("draws the title, a header row, one row per item and the tag at the end", () => {
    render(list());
    expect(screen.getByRole("heading", { level: 1, name: "Things" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Name" })).toBeInTheDocument();
    expect(screen.getAllByTestId("thing-row")).toHaveLength(3);
    expect(within(screen.getAllByTestId("thing-row")[0] as HTMLElement).getByText("System")).toBeInTheDocument();
  });

  it("filters the loaded rows by the search text and says how many are shown", () => {
    render(list());
    fireEvent.change(screen.getByRole("searchbox", { name: "Search things" }), { target: { value: "cust" } });
    expect(screen.getAllByTestId("thing-row")).toHaveLength(2);
    expect(screen.getByText("Showing 2 of 3.")).toBeInTheDocument();
  });

  it("has its own quiet sentence for no match and for an empty list", () => {
    const { unmount } = render(list());
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    expect(screen.getByText("Nothing matches “zzz”.")).toBeInTheDocument();
    unmount();
    render(list({ items: [] }));
    expect(screen.getByText("There are no things yet.")).toBeInTheDocument();
  });

  it("draws no search box when the screen gives no search text", () => {
    render(list({ searchText: undefined }));
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
  });

  it("opens a row from its name and from a click anywhere on the row", () => {
    const onOpen = vi.fn();
    render(list({ onOpen }));
    fireEvent.click(screen.getByRole("button", { name: "Beta" }));
    expect(onOpen).toHaveBeenLastCalledWith(ROWS[1]);
    fireEvent.click(within(screen.getAllByTestId("thing-row")[2] as HTMLElement).getByText("Custom"));
    expect(onOpen).toHaveBeenLastCalledWith(ROWS[2]);
    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  it("opens a row that has an address of its own through a link", () => {
    render(list({ openHref: (row) => `/things/${row.id}` }));
    expect(screen.getByRole("link", { name: "Alpha" })).toHaveAttribute("href", "/things/1");
  });

  it("draws New only when the screen can create, and calls it", () => {
    const onNew = vi.fn();
    const { unmount } = render(list());
    expect(screen.queryByRole("button", { name: "New" })).not.toBeInTheDocument();
    unmount();
    render(list({ onNew, newLabel: "New thing" }));
    fireEvent.click(screen.getByRole("button", { name: "New thing" }));
    expect(onNew).toHaveBeenCalledTimes(1);
  });

  it("shows the API's words instead of the table when the list failed", () => {
    render(list({ items: undefined, failure: "You may not see this." }));
    expect(screen.getByTestId("things-failed")).toHaveTextContent("You may not see this.");
    expect(screen.queryByTestId("things")).not.toBeInTheDocument();
  });

  it("says it is loading until the rows arrive", () => {
    render(list({ items: undefined }));
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });
});

describe("ListPage as a tab, with ordering and a settings menu", () => {
  it("draws no title band when it sits inside a tab, but keeps New, the search box and the table", () => {
    render(list({ embedded: true, onNew: vi.fn(), newLabel: "Start one" }));

    expect(screen.queryByRole("heading", { name: "Things" })).toBeNull();
    expect(screen.getByRole("button", { name: "Start one" })).toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toBeInTheDocument();
    expect(screen.getByTestId("things")).toBeInTheDocument();
  });

  it("puts New before the search box", () => {
    render(list({ onNew: vi.fn(), newLabel: "New thing" }));

    const button = screen.getByRole("button", { name: "New thing" });
    const box = screen.getByRole("searchbox");
    expect(button.compareDocumentPosition(box) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("orders by a header: high to low, then low to high, then as read", () => {
    render(list({ sortPrimary: true }));
    const first = () => within(screen.getAllByTestId("thing-row")[0] as HTMLElement).getByText(/Alpha|Beta|Gamma/).textContent;
    expect(screen.getAllByTestId("thing-row")).toHaveLength(3);
    expect(first()).toBe("Alpha");

    fireEvent.click(screen.getByRole("button", { name: /^Name/ }));
    expect(first()).toBe("Gamma");
    expect(screen.getByRole("columnheader", { name: /Name/ })).toHaveAttribute("aria-sort", "descending");

    fireEvent.click(screen.getByRole("button", { name: /^Name/ }));
    expect(first()).toBe("Alpha");
    expect(screen.getByRole("columnheader", { name: /Name/ })).toHaveAttribute("aria-sort", "ascending");

    fireEvent.click(screen.getByRole("button", { name: /^Name/ }));
    expect(screen.getByRole("columnheader", { name: /Name/ })).not.toHaveAttribute("aria-sort");
  });

  it("draws the settings icon only when there is something in its menu", () => {
    const { unmount } = render(list());
    expect(screen.queryByRole("button", { name: "Things settings" })).toBeNull();
    unmount();

    const pick = vi.fn();
    render(list({ menu: [{ label: "Select fields", onSelect: pick }] }));
    fireEvent.click(screen.getByRole("button", { name: "Things settings" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Select fields" }));
    expect(pick).toHaveBeenCalledTimes(1);
  });
});

describe("DetailPage", () => {
  it("draws the breadcrumb back to the list, the title, the actions and the sections", () => {
    const onBack = vi.fn();
    render(
      <DetailPage listLabel="Things" onBack={onBack} title="Alpha" actions={<button type="button">Edit</button>}>
        <DetailSection title="Detail">
          <DetailGrid>
            <DetailItem label="Kind">System</DetailItem>
            <DetailItem label="Note" wide tone="muted">
              —
            </DetailItem>
          </DetailGrid>
        </DetailSection>
      </DetailPage>,
    );
    expect(screen.getByRole("heading", { level: 1, name: "Alpha" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Detail" })).toBeInTheDocument();
    expect(screen.getByText("Kind")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Things" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("goes back through an address when it is given one", () => {
    render(<DetailPage listLabel="Workflows" backHref="/setup/workflows" title="New workflow" />);
    expect(screen.getByRole("link", { name: "Workflows" })).toHaveAttribute("href", "/setup/workflows");
  });
});

describe("useListRoute", () => {
  function Probe() {
    const route = useListRoute();
    return (
      <>
        <p data-testid="state">{route.creating ? "new" : (route.openId ?? "list")}</p>
        <button type="button" onClick={() => route.open("a b")}>
          open
        </button>
        <button type="button" onClick={route.create}>
          create
        </button>
        <button type="button" onClick={() => route.close()}>
          close
        </button>
      </>
    );
  }

  beforeEach(() => {
    act(() => setUrl("/setup/things"));
  });

  it("keeps the open row and the new form in the address and returns to the list", () => {
    render(<Probe />);
    expect(screen.getByTestId("state")).toHaveTextContent("list");
    fireEvent.click(screen.getByRole("button", { name: "open" }));
    expect(screen.getByTestId("state")).toHaveTextContent("a b");
    fireEvent.click(screen.getByRole("button", { name: "create" }));
    expect(screen.getByTestId("state")).toHaveTextContent("new");
    fireEvent.click(screen.getByRole("button", { name: "close" }));
    expect(screen.getByTestId("state")).toHaveTextContent("list");
  });

  it("starts on the row the address names", () => {
    act(() => setUrl("/setup/things?open=7"));
    render(<Probe />);
    expect(screen.getByTestId("state")).toHaveTextContent("7");
  });
});

describe("useListRoute for a tab of a larger page", () => {
  function Probe({ options }: { options: ListRouteOptions }) {
    const route = useListRoute(options);
    return (
      <>
        <p data-testid="state">{route.creating ? "new" : (route.openId ?? "list")}</p>
        <button type="button" onClick={() => route.open("code__c")}>
          open
        </button>
        <button type="button" onClick={route.create}>
          create
        </button>
        <button type="button" onClick={() => route.close()}>
          close
        </button>
      </>
    );
  }

  beforeEach(() => {
    act(() => setUrl("/setup/objects/Employee__c?section=fields"));
  });

  it("names the open row after what it is and keeps the tab in the address", () => {
    render(<Probe options={{ openParam: "field", keep: ["section"] }} />);

    fireEvent.click(screen.getByRole("button", { name: "open" }));
    expect(screen.getByTestId("state")).toHaveTextContent("code__c");
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields&field=code__c");

    fireEvent.click(screen.getByRole("button", { name: "close" }));
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields");

    fireEvent.click(screen.getByRole("button", { name: "create" }));
    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields&new=1");
  });

  it("does not read the row of another tab", () => {
    act(() => setUrl("/setup/objects/Employee__c?section=record-types&recordType=Renewal__c"));
    render(<Probe options={{ openParam: "field", keep: ["section"] }} />);

    expect(screen.getByTestId("state")).toHaveTextContent("list");
  });

  it("drops what it was not asked to keep", () => {
    act(() => setUrl("/setup/objects/Employee__c?section=fields&view=assignments"));
    render(<Probe options={{ keep: ["section"] }} />);

    fireEvent.click(screen.getByRole("button", { name: "open" }));

    expect(currentUrl()).toBe("/setup/objects/Employee__c?section=fields&open=code__c");
  });
});

describe("the breadcrumb", () => {
  it("links every step but the last, which is the page itself", () => {
    const onClick = vi.fn();
    render(
      <Breadcrumb
        crumbs={[
          { label: "Object settings", href: "/setup/objects" },
          { label: "Account", onClick },
          { label: "Fields" },
        ]}
      />,
    );

    expect(screen.getByRole("link", { name: "Object settings" })).toHaveAttribute("href", "/setup/objects");
    fireEvent.click(screen.getByRole("button", { name: "Account" }));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Fields")).toHaveAttribute("aria-current", "page");
  });

  it("is drawn above the title of a list that is a tab", () => {
    render(
      list({
        title: "Account — Fields",
        crumbs: [{ label: "Object settings", href: "/setup/objects" }, { label: "Fields" }],
      }),
    );

    const trail = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(trail).getByRole("link", { name: "Object settings" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Account — Fields" })).toBeInTheDocument();
  });

  it("is drawn with the steps before the list on a detail page", () => {
    render(
      <DetailPage
        listLabel="Fields"
        parents={[
          { label: "Object settings", href: "/setup/objects" },
          { label: "Account", href: "/setup/objects/Account" },
        ]}
        onBack={vi.fn()}
        title="Name"
      />,
    );

    const trail = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(trail).toHaveTextContent("Object settings/Account/Fields/Name");
    expect(within(trail).getByRole("link", { name: "Account" })).toHaveAttribute("href", "/setup/objects/Account");
    expect(within(trail).getByRole("button", { name: "Fields" })).toBeInTheDocument();
  });
});
