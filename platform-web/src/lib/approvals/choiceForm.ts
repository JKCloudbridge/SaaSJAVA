/**
 * The choice a step of an approval process may ask for together with the approval (Functional track FA6G, ADR-0200).
 *
 * The approval engine does not know what is chosen. A step the process marks as asking for a choice shows the form its
 * object's module registered, takes what the form gives, and sends it with the approval; the module's server side judges
 * it and refuses an approval without it. The browser decides nothing here: a form that says "not ready" only keeps the
 * button from being pressed, and the server asks again.
 */

/** One pair of the choice: what it is about and the code chosen (both short codes of the organization's lists). */
export interface Choice {
  key: string;
  value: string;
}

/** What a registered form is given and gives back. */
export interface ChoiceFormProps {
  /** The record the approval is about. */
  recordId: string;
  /** Called with the choice, or with null while it is not complete enough to send. */
  onChange: (choices: Choice[] | null) => void;
}
