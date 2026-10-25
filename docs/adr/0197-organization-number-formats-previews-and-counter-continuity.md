# ADR-0197: Organization number formats, previews and counter continuity

- **Status:** Accepted (the owner accepted the FA6B recommendations on 2026-10-24)
- **Date:** 2026-10-24
- **Sprint:** FA6B (Functional track)
- **Related:** ADR-0176, ADR-0182, ADR-0184, ADR-0187, ADR-0188, ADR-0196

**Amended by ADR-0198 (FA6C), owner correction on 2026-10-25:** sub-category may be blank even when choices exist.
If the normal category format reads it, a blank value uses the WITHOUT_OPTIONAL default at Save and Approve.
The earlier `requiredOptional` rule below records the original FA6B behavior and is superseded.

## Context

The shipped series is checked at startup and its category formats are fixed. Organizations can add categories and
sub-categories to their lists, but a new category previously had no format. Administrators need to change future number
formats and continue a counter from an earlier system without changing already issued numbers. FA6A places the identity
checks and allocation at the first approval of a template that has no number.

## Decision

### Effective format

Keep the shipped declaration and its startup checks. Add generic `defaults` to the declaration: a closed format with an
optional field, a format without that field, and its API name. The shipped document series declares
`{field:category}/{field:subCategory}/{seq:1}` and `{field:category}/{seq:1}`, with `subCategory` as the optional part.

For each organization resolve: category override, otherwise shipped category format, otherwise the organization's
applicable default, otherwise the shipped applicable default. The optional field's presence chooses the fallback variant;
this is a closed two-way choice, not an expression language. A fallback reads the selector, its optional field when
applicable, and one counter. A category-specific format retains the existing language, including no-counter formats.
Only actual category codes from the organization's published metadata are accepted. Switched-off values are retained.

`numbering_format` (V073) belongs to `data`, with base columns, optimistic version, forced row level security, guard
triggers and a tenant-first unique live key `(tenant_id, series_key, setting_kind, selector_value)`. No other module reads
this table. There is no settings cache and therefore no metadata/security version trigger for this table.

### Consistency and counters

A shared transaction-scoped advisory lock keyed by tenant and series covers resolution and allocation. A setting save or
explicit advance takes the same lock exclusively before writing. The lock also protects a setting with no row yet. The
check and allocation inside an approval see one stable format, while concurrent allocations still share the configuration
lock and serialize only on their particular counter row. The existing allocation upsert and rollback behaviour remain.
The lock order is configuration lock, then counter row; administrative changes never take a template record lock.

The old scope contained the selector and field values in format order. Preserve that key byte for byte whenever the
effective format reads the same field set as its shipped category format, ignoring the already implicit selector.
Otherwise use `numbering-scope-v2`, the selector value and sorted field-name/value pairs. The marker is longer than the
maximum code and cannot be an old scope prefix. V073 expands the counter scope bound to 512 characters. Existing rows
are not rewritten. Reordering fields, changing literals or changing digit width continues the same counter. Different
fields count separately; returning to an earlier field set resumes that counter. An issued number remains unchanged.

The supported running count is at most twelve digits, matching the parser's existing maximum-length calculation. A
counter at its limit refuses another allocation without overflowing or issuing an overlong number. Width remains a
minimum and never truncates a growing count. The unique number field remains the final guard against a format that
would generate an already issued string; no automatic renumbering or overwriting is added.

### Administration and preview

The new `numbering.manage` ability is sufficient and required for all five endpoints. The full-access Organization
administrator profile includes it automatically; no other profile or shipped access policy gains it by default.
`metadata.manage` does not imply it. The page is Setup -> Numbering. It uses the normal list/detail components and the
generated client. The API supplies the rows, formats, code choices, required parts, errors and preview.

Endpoints: `GET /api/v1/numbering`, `PUT /api/v1/numbering/{seriesKey}/formats`,
`POST /api/v1/numbering/{seriesKey}/preview`, and `POST /api/v1/numbering/{seriesKey}/counter`.
Save carries the version read, with zero meaning no organization override yet. The server rejects stale writes.

A preview reads the next count without allocating or reserving it. It is explicitly an observation: another approval
may consume that count first. Candidate formats may be checked before saving. A counter advance always uses the saved
effective format, accepts a next count from 1 through 999999999999, and never lowers the current count. The configuration
lock serializes it with allocations. A never-used scope already starts at 1 and needs no row for that no-op.

### Diagnostics and number display

Audit action, actor, tenant, series key, setting kind and number of parts. Do not store format text, category values,
rendered numbers or chosen next counts in audit, logs, notices or metric tags. Metrics use the action only. Request and
format view string representations are redacted where they carry entered content. Codes and formats travel in bodies.

Fill-in/report rows use one bounded, permission-checked record query for the template numbers on the page. The fill-in
detail uses the ordinary record read. Nobody gains a template number through a fill-in when the normal record/field
permissions withhold it. Every screen shows the stored string; none rebuilds the number from the current format.

## Alternatives and consequences

- A settings cache with version invalidation adds a second consistency mechanism for a tiny settings table. Read afresh
  under a database lock instead; no new module edge is needed.
- Hashing the entire format into a counter key would reset counters for width or literal changes. Keep field identity
  separate from presentation and preserve the old keys.
- Letting preview allocate and roll back would unnecessarily lock the counter and still not reserve the example.
- Counter decrements, rewriting issued numbers, arbitrary expressions, a second allocation door and modifying released
  migrations are excluded. The migration mirror is generated from V073, never maintained separately by hand.

Verification is recorded in `Sprint FA6B.md`; writing a test is not evidence that it has passed.

### Addendum (2026-10-24): a category that offers sub-categories, and going back to the platform's format

- A fallback format that does not read the optional part (category and count) must not number a record silently without
  it when its category offers sub-categories (the dependency of the optional field lists some for the chosen category).
  `NumberingFormats.requiredOptional` names the missing part; `missingParts` reports it and `next` refuses with
  `PART_MISSING`. A category that offers none, such as a new category without sub-categories, numbers as category/count.
- `POST /api/v1/numbering/{seriesKey}/formats/reset` (same ability) ends the organization's override of one setting with
  the version read, so the platform's format applies to later numbers. Counters and issued numbers are untouched; the row
  is soft-deleted, never removed, and a stale version is a `CONCURRENT_MODIFICATION`.
