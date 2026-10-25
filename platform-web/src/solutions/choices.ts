import { createElement, type ComponentType, type ReactElement } from "react";
import type { ChoiceFormProps } from "@/lib/approvals/choiceForm";
import { AudienceChoiceForm } from "./inspection/AudienceChoiceForm";
import { ChangeRequestChoiceForm } from "./inspection/ChangeRequestChoiceForm";
import { REQUEST_OBJECT, TEMPLATE_OBJECT } from "./inspection/template";

/**
 * The forms a shipped solution registers for the approval steps that ask for a choice (FA6G, ADR-0200), by the API name of
 * the object the approval is about. The approval screens know no solution: they ask here, and an object with no form
 * shows a plain sentence instead of an approval that the server would refuse.
 */
const FORMS: Readonly<Record<string, ComponentType<ChoiceFormProps>>> = {
  [TEMPLATE_OBJECT]: AudienceChoiceForm,
  [REQUEST_OBJECT]: ChangeRequestChoiceForm,
};

/**
 * Whether approving the active step of an object needs the choice its form asks for. A step the process marks as asking
 * needs it; the last approval of a change request needs it as well, whether the step is marked or not, because the last
 * approval publishes (FA8, ADR-0202). The server insists regardless.
 */
export function choiceNeeded(object: string | undefined, asksForChoice: boolean, lastStep: boolean): boolean {
  return asksForChoice || (lastStep && object === REQUEST_OBJECT);
}

/** The form for an object, ready to draw, or undefined when this release has none for it. */
export function choiceFormElement(object: string, props: ChoiceFormProps): ReactElement | undefined {
  const form = choiceFormFor(object);
  return form ? createElement(form, props) : undefined;
}

/** The form for an object, or undefined (an inherited property name never counts). */
export function choiceFormFor(object: string): ComponentType<ChoiceFormProps> | undefined {
  return Object.prototype.hasOwnProperty.call(FORMS, object) ? FORMS[object] : undefined;
}
