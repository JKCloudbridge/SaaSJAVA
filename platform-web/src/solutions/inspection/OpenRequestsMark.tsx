/**
 * The asterisk of a template that has a change in progress (Functional track FA6E, ADR-0199; FA8, ADR-0202; FA10, ADR-0204): a small mark
 * in the accent colour after the word of the Change requests tab, inside the tab's own box so the row of tabs never grows a
 * scroll bar because of it, with a text for screen readers. It is a reading
 * aid made from the number the API sent and decides nothing; it draws nothing when the number is zero, and the number is
 * zero once the request is published or declined.
 */
export function OpenRequestsMark({ count }: { count: number }) {
  if (!(count > 0)) {
    return null;
  }
  return (
    <span
      className="open-requests-mark"
      role="img"
      aria-label="Open requests"
      title={count === 1 ? "1 open change request" : `${count} open change requests`}
      data-testid="open-requests-mark"
    >
      *
    </span>
  );
}
