# ADR-0129: The record screens: the member's own description of an object, the names behind links, and "what would the server say"

- **Status:** Accepted
- **Date:** 2026-10-15
- **Sprint:** S19
- **Related:** ADR-0071 (which layout a person gets), ADR-0078/ADR-0079 (validation rules; checking by calling the backend), ADR-0091 and
  ADR-0092 (the write pipeline, field security), ADR-0098 (record types per profile), ADR-0099 and ADR-0107 (the reference directory and what it
  deliberately did not carry), ADR-0100 (the query engine), ADR-0124 (search), ADR-0125 (record security on writes), ADR-0126 (design system),
  ADR-0127 (the scoped token these endpoints are inside), ADR-0128 (navigation)

## Context

Sprints 10 to 17A built everything a record screen needs on the server, and Sprint 12A and Sprint 18 built the shell. Sprint 19 is the first
screen that draws *records*, for any object a tenant defines, with no code of its own. Checking the plan against the code found four things the
prompt did not say:

1. **A plain member could not read what a form is made of.** Every `/api/v1/metadata/...` read (object, fields, layouts, record types) needs
   `metadata.view`, an administrator's ability. `/data-catalogue` is for people who manage access and has no field types. So a member with
   permission on the data had nowhere to learn which fields exist, what type they are, what the layout is or which record types they may use.
2. **The server already holds every decision a screen would otherwise copy** (which fields a member may read and edit, which comparisons a
   field takes, which record types a profile may use, which layout applies), in four different classes. A browser that re-derived them would
   drift.
3. **A person's name could not be read.** ADR-0099 made the reference directory a yes/no on purpose and ADR-0107 said a name through it is "a
   different contract, built when a screen needs the name of an owner (Sprint 19)". The owner column is that screen.
4. **ADR-0078 and ADR-0079 promised** that Sprint 19 would give "the browser's immediate feedback with real records" by calling the backend,
   not by a second expression engine in TypeScript.

## Decision

### 1. One read for a member: `GET /api/v1/objects/{object}/form`

`ObjectForms` (module `data`, beside `RecordGate` and `FieldSecurity`, so it uses the same sources as the write pipeline) answers, for the
signed-in member and one record type, in a single `ObjectFormView`:

- **the fields they may read**, nothing else: a field they may not read is absent, not masked (ADR-0092). A record type offers only some fields
  (`RecordTypeCheck.offers`, the same function the pipeline uses) and allows only some picklist values. The system fields a record's answer
  cannot carry (the identifier, who created, who changed) are not offered as columns;
- **per field, what they may type**: `editable` is the member's field permission, the object permission, "a person writes no system field and no
  calculated one", and the page layout's behaviour for the field, together. The pipeline judges the write again; this only keeps the screen from
  offering what will be refused. A formula is described by its result type, **never its text** (the text is an administrator's, and the member's
  need is to read a result);
- **per field, what a list may ask**: the comparisons a filter takes and whether it can be an order key, from `QueryAnalyzer.queryingOf`, which
  reads the very tables the engine uses, so the description and the engine cannot disagree;
- **the layout** the object uses for the member's profile and the record type (`Metadata.layoutFor`, ADR-0071), reduced to the fields in front of
  them (a hidden one is left out), or, when the object has none (or none of its fields is in front of this member), one the platform makes
  (`GENERATED`: the fields, then the system fields in a block of their own). A layout always shows the required fields of its object (the
  metadata refuses one that does not, ADR-0069), so a form built from it can be saved and nothing is added to it;
- **the record types** the member's profile may use (ADR-0098), the default marked; the type a record already holds is described even when the
  profile has lost it ("a record keeps the type it has"), flagged `selectable: false`. Asking for a type by name that the profile may not use
  answers like a type that does not exist, and an inactive one is refused in the words of the write ("This record type is not in use.");
- **the title field** (`TitleFields`, one rule): the first searchable text field the member may read, else the first required text field, else
  the first text field, else the auto-number. A record is called the same thing in a list, a heading and a link;
- the object's labels, what the member may do with the object (`create`, `update`, `delete`, presentation only), and whether a search can be run.

It is **presentation**: every request that follows is decided again. The embedded access policy (ADR-0127) already allows `/objects/**` below, so
a custom page can draw a screen from the same answer.

### 2. The names behind links: `ReferenceLabels` and `/lookups/{field}/labels|options`

A new shared-kernel contract, `ReferenceLabels` (the "different contract" ADR-0107 named), implemented by `identity.internal.UserLabels` for
`User`. It names only what a link could point to **now**: a person with an *active* membership of the current organization, by display name. A
missing identifier, one of another organization and a deactivated person all get no name, so the answer cannot tell them apart. It reads no
address and no status. `ReferenceDirectory` (yes/no) is unchanged.

`LookupService` (module `data`) serves two calls for a link field the member may read:

- **labels** (`POST`, at most 100 identifiers): for a person, `ReferenceLabels`; for an ordinary object, `Records.query` with the target's title
  field, which applies the *target's* object permission, field permissions and record-level security to this member. A name that is missing
  says nothing about why (unreadable, gone, in the bin, an object the member may not read). A member who may read the object but not its
  identifier as a field cannot filter by identifier, so those records are read one at a time through the same `RecordVisibility` builder
  (`storage.find` with the same condition), never through a second rule;
- **options** (`POST`, 10 by default, at most 25): matching names for a picker, by the ranked search when the target has a searchable field the
  member may read, otherwise by a match on the title field. **The text is in the body, not the address**: a name typed into a picker is personal
  data and an address is written into the logs of every proxy on the way (the same reason `records/search` is a POST).

**Privacy consequence, decided here and to be reviewed by the product owner:** any member who may read an object can learn the display name of
the *active members of the organization* through the owner column and the person picker. This is what every record system shows ("owned by
user-a"); nothing about people outside the organization, deactivated members, addresses or any other attribute is reachable.

One trap this design avoids, recorded because it is easy to fall into: an `ApiException` thrown **through the transactional record store**
(`Records.query`, `Records.get`) marks the caller's own transaction rollback-only even when the caller catches it, so the commit then fails with
a 500. `LookupService` therefore never uses an exception from the store as control flow: the checks that can refuse (the target object, the
member's right to read it) are made first through `RecordGate` (not transactional), and an unreachable record is simply absent from a read that
does not throw. `LookupIT` has the case that found it.

### 3. "What would the server say?": `POST /api/v1/objects/{object}/records/validate`

`RecordCheck` runs the **real** `WritePipeline` for a create (no `recordId`) or a change (a `recordId`: first the same record-permission stage as
a real change, so a record the member cannot reach is "not found" and one they may not change is "forbidden") and then **rolls the transaction
back**. Everything the pipeline did is undone together: the auto-number it took, the locks it held. Nothing is stored, audited or announced.
A save that would be refused is refused with the same `VALIDATION_ERROR`, the same places and the same words, because it is the same code; one
that would work answers with the results the formulas would give for the fields the member may read and whose result the save would change.
No expression engine exists in the browser: validation rules and formulas stay the server's (ADR-0078, ADR-0079). `RecordCheckIT` proves it:
three validations use no auto-number, leave no audit record, event or record behind, and every refusal equals the real save's.

### 4. What the browser does (the frontend rules, in one place)

- **A field component registry** (`FieldRegistry.tsx` with `fieldValue.ts`): one table maps each of the 19 field types to a control and a
  reading. `fieldValue.test.ts` lists the 19 and fails until a new type is registered. A list cell reads like a read-only field, cut short.
  Inline cell editing is **not** built: a lookup, a multi-select, a long text and a date and time need richer controls than a cell, and the bulk
  change plus the form cover editing; the mockup's `.editing` cell is a visual option, not a requirement.
- **Hints before a save** (`hintFor`) point at what the field says about itself (required, length, digits, decimals), in the server's words, and
  never say a value is acceptable. The form's preview asks the server a moment after a change (§3). The save sends **only what changed**, and a
  field emptied is cleared.
- **Numbers travel exactly.** The API reads a number as an exact decimal and refuses text, and a JavaScript number rounds beyond 15 digits, so a
  number the person typed is kept as the digits typed (`DecimalText`) and written by a small serializer that writes the JSON tree itself, so no
  typed text can ever be taken for a number. (The first design, a marker string replaced after `JSON.stringify`, was rejected by its own test:
  the marker survived as an ordinary string and a typed text could forge it.) Values read back are ordinary JSON numbers; a value of more than
  15 significant digits may *display* rounded, and is never re-sent unless the person changes it.
- **Conflicts: reload and reapply.** On `409 CONCURRENT_MODIFICATION` nothing was saved; the form reads the newest record and compares three
  versions (as opened, as now, as typed), field by field (`conflict.ts`, a pure function): fields only the person changed are kept, fields only
  the other change touched are listed so nothing changes unannounced, and a field both changed differently is a choice. The newest version
  becomes the base and the person **reviews and saves again**; the server judges that save like any other. There is no way to overwrite the
  other change blindly. A record that is gone or no longer visible says so.
- **Search is the ranked search** (`POST .../records/search`), never a substring filter of the page; while a search is shown the ordering and
  filters do not apply (the API does not combine them) and the screen says so. **Bulk** is `POST .../records/bulk` (per-record outcomes, the
  version each row was read at, an all-or-none switch); a record someone changed since the list was read fails by itself with a plain sentence.
  **Import** reads only the first line of the file (to offer the columns); the server reads the file for real.
- **No total.** The engine gives none (ADR-0105), so the views rail shows no count, unlike the mockup's.

### 5. Where the screens live

`/records/<object>`, `/records/<object>/new` and `/records/<object>/<id>` (the address the system field `id` always named), and the same three
inside an application through its entry (`/apps/<application>/o-<object>`, `/new`, `/records/<id>`), so the control bar keeps the application
open. Neither address is a permission; a page the API does not give is "not available" in one set of words.

## Consequences

- Sprint 20 composes record pages from the same description and the same form component; a `CUSTOM` tab on an object page reuses ADR-0127.
- The description is read per screen load; the browser does not cache it across loads, so a permission or layout change shows at the next
  open. A short-lived cache can be added behind the same answer if a measurement asks for it.
- `FormFieldView`, `ObjectFormView`, `LookupLabels*`, `LookupOptions*`, `ValidateRecordRequest` and `RecordCheckView` are new contract types;
  `RecordView` is unchanged.
- Tested: `ObjectFormIT`, `LookupIT`, `RecordCheckIT`, `RecordScreensLogsAreCleanIT`, `QueryingTest`, `TitleFieldsTest`; on the browser side the
  registry, the form, the list, the import panel, the picker and the page tests.
