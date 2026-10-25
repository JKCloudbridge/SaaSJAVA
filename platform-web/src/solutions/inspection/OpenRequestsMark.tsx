/**
 * The asterisk of a template that has change requests waiting for someone (Functional track FA6E, ADR-0199): a small mark in
 * the accent colour with a text alternative. It is a reading aid made from the number the API sent and decides nothing; it
 * draws nothing when the number is zero.
 */
export function OpenRequestsMark({ count }: { count: number }) {
  if (!(count > 0)) {
    return null;
  }
  return (
    <span
      className="open-requests-mark"
      role="img"
      aria-label="Has open change requests"
      title={count === 1 ? "1 open change request" : `${count} open change requests`}
      data-testid="open-requests-mark"
    >
      *
    </span>
  );
}
