import { createElement, type ComponentType, type ReactElement } from "react";
import type { ChoiceFormProps } from "@/lib/approvals/choiceForm";
import { applicationOfObject, roleOfObject } from "@/lib/applications/objectRoles";
import { AudienceChoiceForm } from "./inspection/AudienceChoiceForm";
import { ChangeRequestChoiceForm } from "./inspection/ChangeRequestChoiceForm";
import { REQUEST_PART, TEMPLATE_PART } from "./inspection/objects";
import { setScope } from "./inspection/scope";

/**
 * The forms a shipped solution registers for the approval steps that ask for a choice (FA6G, ADR-0200), by the part the
 * object plays in an application of the kind (FA13B, ADR-0226): the API lists which object plays which part, so the
 * forms follow the objects of every application of the kind and name none. The approval screens know no solution: they
 * ask here, and an object with no form shows a plain sentence instead of an approval that the server would refuse.
 */
const FORMS: Readonly<Record<string, ComponentType<ChoiceFormProps>>> = {
  [TEMPLATE_PART]: AudienceChoiceForm,
  [REQUEST_PART]: ChangeRequestChoiceForm,
};

/**
 * Whether approving the active step of an object needs the choice its form asks for. A step the process marks as asking
 * needs it; the last approval of a change request needs it as well, whether the step is marked or not, because the last
 * approval publishes (FA8, ADR-0202). The server insists regardless.
 */
export function choiceNeeded(object: string | undefined, asksForChoice: boolean, lastStep: boolean): boolean {
  return asksForChoice || (lastStep && object !== undefined && roleOfObject(object) === REQUEST_PART);
}

/** The form for an object, ready to draw, or undefined when this release has none for it. */
export function choiceFormElement(object: string, props: ChoiceFormProps): ReactElement | undefined {
  const form = choiceFormFor(object);
  if (!form) {
    return undefined;
  }
  // The form asks the server about the records of the application the object belongs to: the approval screens are not
  // inside an application page, so the scope is set from the object (inspection/scope.ts).
  const application = applicationOfObject(object);
  if (application) {
    setScope(application);
  }
  return createElement(form, props);
}

/** The form for an object, or undefined (an inherited property name never counts). */
export function choiceFormFor(object: string): ComponentType<ChoiceFormProps> | undefined {
  const role = roleOfObject(object);
  return role !== undefined && Object.prototype.hasOwnProperty.call(FORMS, role) ? FORMS[role] : undefined;
}
