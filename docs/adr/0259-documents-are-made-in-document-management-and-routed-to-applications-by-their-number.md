# ADR-0259: Documents are made in Document Management and routed to applications by their number

- **Status:** Accepted (the product owner redesigned Management Core on 2026-11-14 and answered the six questions of the MC1.1 plan with the
  recommendations)
- **Date:** 2026-11-14
- **Track:** Functional (Management Core, part 1.1; a rework of MC1). Migration V092 (expand only). One field added to three shipped
  objects, one table, one notice kind, one mail template, one access policy. No new module edge. **Supersedes in part ADR-0257** (see the end).
- **Related:** ADR-0226 (applications of one kind on shared code), ADR-0225 (applications that share objects and policies), ADR-0197 and
  ADR-0206 (number formats, identification and parts), ADR-0145 and ADR-0187 (the approval engine and its hooks), ADR-0198 (the platform writes
  its own fields inside the write pipeline), ADR-0154 (notices are facts, never words), ADR-0115 (one place decides who sees a record),
  ADR-0003 (row level security), ADR-0012 (what is never logged)

## Context

MC1 made Management Core an application of its own, with three objects of its own (a form, a fill-in and a change request), six access
policies and a number series that borrowed the counters of Document Management. The owner then decided the design was wrong: **every
application of the platform makes its documents in Document Management**, by the chain that already works (a department or management user
creates the document; Plant QMS sets the identification, numbers it and approves; Corporate QMS approves and suggests the plant; Plant QMS
publishes to the creator's group). The number then decides which application the document also belongs to: identification `F` with Part A
`MC` is Management Core, `F` with `PROD` could be a production application, and so on. Each organization sets its own combinations. Nothing is
in production, so what MC1 made and the new design does not need can go.

## Decisions

### 1. Routing rules are the organization's data: Setup, Numbering, Application routing

The table `inspection_document_routing` (V092) holds one row for each combination of Identification and Part A (an empty Part A stands for every
Part A), naming an application. It is tenant-scoped with forced row level security, has a guard (the combination of a row never changes, a removed
row is never changed) and a partial unique index, so a combination has one live rule. It is managed through `/api/v1/document-routing` with the
ability `numbering.manage`; the Identification must be a value of the organization's own list and Part A obeys the rule of Part A (capitals,
digits and hyphens, at most ten). A rule names only an application **that declares in its solution file that its documents come from Document
Management** (the option `routedByNumber`) and that the organization has; a rule for an application the organization lost is shown as
unavailable, does nothing, and can be removed. The Identification and Part A are the organization's own text: they are never logged and never put
in an audit record (the audit says the application's key). The exact pair wins over the Identification alone, **even when the pair's
application is gone**: the pair is the explicit statement, so such a document simply stays in Document Management alone.

### 2. The application is written on the document once, by the platform, when its number is made, and is fixed with the number

Each of the three objects (document, fill-in, change request) has a new field `application`, written only by the platform (`platformWritten`),
indexed (the narrowing reads it). The platform writes it in the same write as the number: at a permitted **Save** (`TemplateIdentityWrites`, the
record write rule of ADR-0198) and at the **first approval** (`TemplateApprovalHooks`, which already fixes the number). Until the first approval
fixes the number, a new Save follows the rules as they are (the number can still change, so the application follows it); **at the first approval
the rules are looked up once more**, so a document numbered before its rule existed gets its application then. A rule that no longer fits
is not cleared at the first approval (the write pipeline takes no empty value, and a Save is where a value is cleared): what the last Save gave stays. From then on **a later rule never moves the document**. The application is never derived from the text of the number at read time.
A document whose number matches no rule has the field empty and belongs to Document Management alone, exactly as every document did before.

### 3. One fill-in object and one change-request object for all applications

There is no second set of objects. The fill-in header and the change-request header get the same field, **copied from the document** when the
fill-in is started and when the request is raised, so every list, report, dashboard and approval process knows the application of a fill-in or
a request without asking the document again.

### 4. Approval processes: one for documents, one for fill-ins of each application, chosen by the engine

The document's own approval is Document Management's and the same for every application: a process is chosen at Submit and pinned, and the
application is known only after Plant QMS numbers the document. An application extends the flow in three places, all data: its own act after the
publish (MC2A), **its own approval process for fill-ins**, and optionally its own for change requests. The approval engine already chooses the one
active process whose entry criteria the record meets and refuses when two fit; the entry criteria are an expression over the fields of the
object, so a platform-written text field works and `ISBLANK(application)` and `application = 'management-core'` are the two criteria that
never both fit. Nothing in the engine changed.

### 5. A routed application is Document Management's records narrowed by that field

`RecordSet` gains an optional `application`: a **narrowed set** has the same objects and series and one more condition. The solution file says
`options: routedByNumber: "true"`; the application then needs no objects, no policies and no numbering file of its own and lists the Document
Management objects under `uses`. `RecordSets` checks at start that every source of records has exactly one set that shows all of them, and that
no object belongs to two sources. The narrowing is applied as an extra condition of the record store's own question (`RecordSet.narrow`) in every
list, count, search, report and dashboard of the cluster, and `TemplateAccess.visible` (the gate of every call about one document) answers "not
found", in the same words as an unknown document, for a document that carries another application. **It chooses; it never grants**: who may read
a record is still the record store's and the decision API's answer. A narrowed application offers no New document (`canCreate` is false):
documents are made in Document Management.

One consequence to know: a field a member may not read does not exist for them, in a filter too. The shipped access policies therefore list
`application` in their read lists (a test keeps it so); a policy an administrator wrote without it makes a narrowed list answer with the problem
and never shows more.

### 6. Options belong to the application of the document

`footerDate` is looked up from the document's own `application` value (`RecordSets.footerDate`), so the day is on the footer of a Management
Core document wherever it is shown, also in Document Management, and on no other document. It stays independent of whether the organization has
the application now: it is a property of the document. `publishTo: places` and `publishDepartment` stay declared for the act that publishes to
plant heads (MC2A); `RecordSet.withoutPublishOptions` makes the publish of Document Management ignore them in this release.

### 7. The creator is told when a document is published (every application)

A new notice kind `DOCUMENT_PUBLISHED` (category `DOCUMENTS`, V092) and a new mail template of the same name. The notice holds facts only (an
event key, the application and the entry of the page that opens the document; the recipient is the document's owner; nobody is told of their own
act); the mail is queued by the notification module together with the notice and carries the same facts and the object's name, never a title or a
number. The person switches either channel in the preferences; a mail is built at send time with a link on the organization's own host that
opens the document and decides nothing. The key is the document and the stored number of the version, so a repeat tells nobody twice and a new
Revision tells again. The link leads to the application the document was routed to when the organization has it.

### 8. One access policy that sees everything and changes nothing: Observer

For top management: the rights to read and view all on documents, fill-ins and change requests, no right to create or change, no ability, and the
read lists of the fields. Documents are fixed private, so the role hierarchy gives no sight of them: groups and policies do. The policy comes with
every application that uses the objects (`alsoAvailableWith`).

### 9. What MC1 made and this design does not need is removed

The objects `ManagementTemplate`, `ManagementFill` and `ManagementChangeRequest`, Management Core's numbering file and its series, the six
Management policies, `ManagementCoreParityTest`, `SharedCounterIT`, the MC1 parts of the demo script, and the generic `counterSeries` support
(it had no other user). The rule "never delete or rename a released definition" is **waived for these three objects by the owner's decision**:
nothing was in production, and the baseline and the generated list of objects were regenerated. Kept: the bundle options, the places-only
audience, the footer day, the tiles and tabs of the application. `platform.sheets.format-two.owners` in the local profile is empty again, so every
owner makes the new format.

## Consequences

- A new application of this kind is a solution file (and, for its own extensions, files and Setup); no class names an application
  (`InspectionArchitectureTest`).
- A person who may not read the `application` field cannot use a narrowed list; every shipped policy lists it.
- The approvals inbox is one list of a person's tasks across applications; it is not narrowed. A task notice leads to the application of the
  document.
- A pair rule shadows the broader rule even when its application is gone; the administrator sees the rule marked unavailable and can remove it.
- Management Core needs Document Management in practice (documents are made there); the platform does not enforce it.

## Supersedes in part ADR-0257

The decisions on the shared counter (1), on the owners of the new format as a local list (4) and the three objects and six policies of the
application are replaced; the decisions on publishing to places (2, kept as an option for MC2A) and the footer day (3, kept, now following the
document) stand.
