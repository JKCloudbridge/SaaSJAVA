# ADR-0104: Keyset paging with records that have no value

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0011](0011-api-conventions.md), [ADR-0100](0100-the-query-model-and-its-stages.md), [ADR-0101](0101-the-index-policy-and-the-slots.md)

## Context

Offset paging gets slower with depth and skips or repeats rows when data changes (ADR-0011), so every collection pages by key. A sort key is a nullable column in a partial index that holds only records with a value (ADR-0101). The hard part of keyset paging is exactly this: the position after
a record whose value is null, and ties.

## Decision

1. **The order is the keys, then the identifier** (in the direction of the last key; ascending when there is no key). Records without a value come last in both directions. Two keys at most.
2. **A page is read in at most two steps (phases).** First the records that have a value in the first key, ordered by the keys and the identifier, using the index; then, if the page is not full, the records that have none, ordered by the remaining keys and the identifier, using the identifier index. A cursor
   inside the first phase continues it; a cursor whose first value is empty goes straight to the second.
3. **With one key the position is one comparison of a pair**: `(value, id) > (cursor value, cursor id)` (or `<`), which PostgreSQL uses as an index condition and starts the scan at the cursor. With two keys the position is the long form (`k1 after v1 or (k1 = v1 and (k2 after v2 or (k2 = v2 and id after i)))`)
   with empties handled at each level; correct, and the first key still uses its index. (Measured: ADR-0109.)
4. **The cursor** carries the sort values of the last record as text (the values the database read, so the cursor repeats exactly what was compared), its identifier and a short fingerprint of the query (object, filter, order, not the page size). A cursor of another query, with the wrong number of values, or
   not made by this API is `VALIDATION_ERROR` at `cursor`. It is not signed (ADR-0011).
5. **The weakest part, on purpose in the open:** reaching the second phase after paging through all the records that have a value reads the identifier index and skips those that have a value, which is one scan of the object. It is reached only by paging to the end, the statement time limit is the safety net
   (ADR-0108), and an object where most records lack the value reaches it at once and finds them quickly. A property test holds the whole construction to an in-memory model.
6. **Concurrent writes:** a record that does not change appears exactly once whatever else is written while the pages are read; a record that changes its sort value while the pages are read may be seen twice or not at all, as with any keyset paging.

## Consequences

- Deep pages cost the same as the first; the only slow path is the second phase of a sort over a nearly full field.
- The API never offers "page 7 of 40".
