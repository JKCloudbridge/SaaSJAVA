# ADR-0198: Save-time template numbers and compatible fill-in formatting

Status: Accepted by the owner's revised FA6C prompt, 2026-10-24. Amends ADR-0196's allocation point and extends
ADR-0192/0193's fill-in document. ADR-0197's format precedence and counter continuity remain in force.

## Decision

An ordinary record Save by a member permitted to set the identity category allocates a real number when the effective
format's parts are complete. The trusted `RecordWriteRule` contribution runs inside the existing `WritePipeline`, after
member field checks and before uniqueness/rules/formulas. It may contribute only declared platform-written fields.
Create, update, imports and bulk member writes use that pipeline. Domain code never calls storage to write a header.
Platform background writes enforce fixed identities but leave allocation to the approval backstop.

Before first Approve the identity may change. A Save with the same selector and effective input values keeps its number;
a change to an input the format reads calls only `NumberSeries.next` for a replacement. Clearing required inputs clears
the provisional header number. Earlier numbers remain permanently owned. For example, WI/PRD/01 -> WI/QLT/01 ->
WI/PRD/02 consumes two counts in PRD and one in QLT. Gaps are accepted. Typed formats without a counter may return to a
spelling already reserved by that same record, but never one reserved by another record.

V074 adds `number_series_reservation`, with a tenant-first unique key on series and spelling, a record lookup index,
forced RLS, and a guard against deletion, reassignment or reversing a fixed mark. Reservations and identifier-only
`numbering.assigned` audit records share the header's transaction. Failure rolls back all of them and the allocator.
The allocator skips reserved spellings, including ones another scope could produce after a format change, and checks
existing stored headers so historical numbers remain protected. No format is rewritten and counters are never reset.

First successful Approve fixes the current reservation, even when the header was numbered at Save. Identity fields
become read-only in the server's form description and a changed identity is refused with fixed words on every pipeline
route. A later rejection cannot unfix it. Rejection before any Approve retains provisional numbering and editable
identity. Legacy numbered headers without reservations are treated as already fixed because they were numbered at
approval under ADR-0196. The approval hook still allocates an unnumbered header through its existing narrow writer.

Owner correction, 25 October 2026: sub-category is optional even when choices exist. If a selected format reads it and
it is blank, resolve the organization's WITHOUT_OPTIONAL default (shipped category/count). This applies at Save and
Approve, including category overrides. Selecting it restores the category's normal format; clearing it before first
Approve takes the next category-only count. Issued numbers stay reserved. Shipped QSP deliberately ignores it. Adding a picklist value selects
its returned code and persists the dependency pair; it cannot change a numbering format. Organization overrides are the
administrator's way to choose a sub-category format. Repeated additions remain idempotent.

## Fill-in document

Version 1 remains readable and writable with its exact canonical representation. Opening, saving without formatting,
or archiving an old document does not upgrade it. Formatting creates version 2:

```json
{"formatVersion":2,"rows":[{"id":"r1","cells":{"c1":"Checked"},"styles":{"c1":{"bold":true,"italic":false,"fontSize":"LARGE","underline":true,"alignment":"CENTER","colour":"ACCENT"}}}]}
```

Styles are sparse column-keyed maps. `bold`/`italic`/`underline` booleans and four existing font sizes are accepted. Explicit
false overrides inherited formatting; resetting removes overrides. A style may target only an input cell. Header/fixed
cells, merges, column definitions, widths and heights remain in the published template. Optional `alignment` accepts
LEFT/CENTER/RIGHT; `colour` accepts DEFAULT/ACCENT/SUCCESS/DANGER and maps to theme tokens. No executable markup or
arbitrary CSS is accepted. Following the owner's approval, Underline/alignment/colour work in both editors through
shared controls. Unavailable structural tools in fill-ins are disabled with fixed explanations. Template default
styles are omitted to preserve existing canonical bytes; fill-in explicit defaults can override inherited styles.

The existing staged validator checks raw bytes, depth, duplicate keys, shape, limits, row identities and cell permissions.
Styles share the same byte limit. Canonical objects sort every key; style-only named rows remain sparse stored rows but
cannot satisfy a required answer. Hashes and archives include styles. The reportable-values projection reads only
`cells`, so no style columns or migration are needed. V074 is for numbering, not formatting.

Draft/returned fill-ins autosave after two seconds of inactivity. One queue serializes debounce, manual Save, Submit and
leaving; it carries forward the row version and does not acknowledge newer edits as saved by an older response. Any
refusal keeps local values/styles and pauses automatic retries. Conflict reload/reapply merges local changes into the
latest permitted draft, preserving unrelated remote changes. Submitted/approved/archived documents never autosave;
authorized manual corrections retain their existing rules. No business data is persisted in browser storage.

Links, Cancel and registered page-back actions wait for saving. A same-document browser history traversal while dirty
restores the editor before the router leaves, then resumes the destination on success; this can add a history entry.
Closing/reloading a browser cannot promise completion of a large request, so the unsaved warning remains.

## Notices and messages

`POST /api/v1/notifications/{id}/open` checks ownership, marks read and asks the domain's `NoticeDestination` to resolve
the stored object/record facts through ordinary read permissions. Template targets use the canonical application entry,
including when an older approval notice has no application/entry fact. Unsupported/unreadable targets return the notices
list and fixed explanation. Target reads run with their own transaction so a caught access refusal cannot roll back
marking the notice read. Nothing typed is stored in notice prose.

The bell badge sits inside its box; notices use the shared searchable/orderable `ListPage`. One `ActionNotice` expires
successes after three quiet seconds, pauses on hover/focus, and keeps errors. Repeated identical successes have distinct
occurrences. Decisions and business permissions remain on the server.

## Verification boundary

Development compilation and API generation are separate from acceptance testing. After the owner authorized testing,
focused FA6C behavior checks ran and passed after two test corrections. The manual browser rehearsal and CI remain.
See the sprint write-up for exact commands, results and limits.
