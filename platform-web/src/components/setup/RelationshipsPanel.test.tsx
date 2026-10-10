import { render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeApi, json, refusal } from "@/test/fakeApi";
import { RelationshipsPanel } from "./RelationshipsPanel";

afterEach(() => {
  vi.unstubAllGlobals();
});

const PATH = "GET /api/v1/metadata/objects/Department__c/relationships";

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
  it("shows the parents, the lists on this object and the many-to-many ones, with what happens on removal", async () => {
    fakeApi({ [PATH]: () => json({ data: { parents: [PARENT], children: [CHILD], manyToMany: [MANY] } }) });

    render(
      <StrictMode>
        <RelationshipsPanel objectApiName="Department__c" />
      </StrictMode>,
    );

    const parents = await screen.findByTestId("relationship-parents");
    expect(parents).toHaveTextContent("Company__c through company__c");
    expect(parents).toHaveTextContent("may move to another master");
    expect(parents).toHaveTextContent("the record is removed too");
    const children = screen.getByTestId("relationship-children");
    expect(children).toHaveTextContent("Staff");
    expect(children).toHaveTextContent("one to many");
    expect(children).toHaveTextContent("the removal is refused");
    expect(children).toHaveTextContent("required");
    expect(screen.getByTestId("relationship-many")).toHaveTextContent("Course__c through Enrolment__c");
  });

  it("says so when an object has no relationships", async () => {
    fakeApi({ [PATH]: () => json({ data: { parents: [], children: [], manyToMany: [] } }) });

    render(<RelationshipsPanel objectApiName="Department__c" />);

    expect(await screen.findByText(/has no relationships yet/)).toBeInTheDocument();
  });

  it("shows nothing when the API does not answer with a list, for example for a person who may not look", async () => {
    fakeApi({ [PATH]: () => refusal("FORBIDDEN", "You are not allowed to perform this action.", 403) });

    render(<RelationshipsPanel objectApiName="Department__c" />);

    await vi.waitFor(() => expect(screen.queryByTestId("relationships")).toBeNull());
  });
});
