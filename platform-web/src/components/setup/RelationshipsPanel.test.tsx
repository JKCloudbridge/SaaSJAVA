import { fireEvent, render, screen, within } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { tabProps } from "@/test/objectTab";
import { RelationshipsPanel } from "./RelationshipsPanel";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = "GET /api/v1/metadata/objects/Department__c/relationships";
const DEPARTMENT = { apiName: "Department__c", label: "Department", kind: "CUSTOM", editable: true, extensible: true, fields: [] };

const CHILD = {
  type: "ONE_TO_MANY", fieldType: "LOOKUP", childObject: "Employee__c", field: "department__c", fieldLabel: "Department",
  parentObject: "Department__c", otherObject: "Employee__c", onDelete: "REFUSE", reparentable: false, required: true,
  listLabel: "Staff",
};
const PARENT = {
  type: "MANY_TO_ONE", fieldType: "MASTER_DETAIL", childObject: "Department__c", field: "company__c", fieldLabel: "Company",
  parentObject: "Company__c", otherObject: "Company__c", onDelete: "CASCADE", reparentable: true, required: true,
  listLabel: "Departments",
};
const MANY = {
  type: "MANY_TO_MANY", fieldType: "MASTER_DETAIL", childObject: "Enrolment__c", field: "student__c", fieldLabel: "Student",
  parentObject: "Department__c", otherObject: "Course__c", viaObject: "Enrolment__c", onDelete: "CASCADE",
  reparentable: false, required: true, listLabel: "Enrolments",
};

describe("RelationshipsPanel", () => {
  it("lists the parents, the lists on this object and the many-to-many ones, with what happens on removal", async () => {
    fakeApi({ [PATH]: () => json({ data: { parents: [PARENT], children: [CHILD], manyToMany: [MANY] } }) });

    render(
      <StrictMode>
        <RelationshipsPanel {...tabProps(DEPARTMENT)} />
      </StrictMode>,
    );

    const rows = await screen.findAllByTestId("relationship-row");

    expect(screen.getByRole("heading", { name: "Department — Relationships" })).toBeInTheDocument();
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Company");
    expect(rows[0]).toHaveTextContent("Points to");
    expect(rows[0]).toHaveTextContent("company__c");
    expect(rows[0]).toHaveTextContent("Many to one");
    expect(rows[0]).toHaveTextContent("may move to another master");
    expect(rows[0]).toHaveTextContent("the record is removed too");
    expect(within(rows[0] as HTMLElement).getByRole("link", { name: "Company__c" })).toHaveAttribute(
      "href",
      "/setup/objects/Company__c",
    );
    expect(rows[1]).toHaveTextContent("Staff");
    expect(rows[1]).toHaveTextContent("Listed here");
    expect(rows[1]).toHaveTextContent("One to many");
    expect(rows[1]).toHaveTextContent("the removal is refused");
    expect(rows[1]).toHaveTextContent("required");
    expect(rows[2]).toHaveTextContent("Many to many");
    expect(rows[2]).toHaveTextContent("Course__c");
    expect(rows[2]).toHaveTextContent("Enrolment__c");
  });

  it("searches the relationships", async () => {
    fakeApi({ [PATH]: () => json({ data: { parents: [PARENT], children: [CHILD], manyToMany: [MANY] } }) });
    render(<RelationshipsPanel {...tabProps(DEPARTMENT)} />);
    await screen.findAllByTestId("relationship-row");

    fireEvent.change(screen.getByRole("searchbox", { name: "Search relationships" }), { target: { value: "staff" } });

    expect(screen.getAllByTestId("relationship-row")).toHaveLength(1);
  });

  it("says so when an object has no relationships", async () => {
    fakeApi({ [PATH]: () => json({ data: { parents: [], children: [], manyToMany: [] } }) });

    render(<RelationshipsPanel {...tabProps(DEPARTMENT)} />);

    expect(await screen.findByText(/has no relationships yet/)).toBeInTheDocument();
  });

  it("asks again when the object was read again", async () => {
    const calls = fakeApi({ [PATH]: () => json({ data: { parents: [], children: [], manyToMany: [] } }) });
    const { rerender } = render(<RelationshipsPanel {...tabProps(DEPARTMENT)} />);
    await screen.findByText(/has no relationships yet/);

    rerender(<RelationshipsPanel {...tabProps(DEPARTMENT, { refreshToken: 1 })} />);

    await vi.waitFor(() => expect(calls.filter((call) => call.key === PATH)).toHaveLength(2));
  });

  it("shows the API's words when it does not answer with a list, for example for a person who may not look", async () => {
    fakeApi({ [PATH]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });

    render(<RelationshipsPanel {...tabProps(DEPARTMENT)} />);

    expect(await screen.findByTestId("relationships-failed")).toHaveTextContent("You are not allowed to perform this action.");
  });
});
