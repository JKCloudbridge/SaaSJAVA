# ADR-0260: The creator publishes a document to the plants, the reviewers of a plant, and the monthly fill-in

- **Status:** Accepted (the product owner answered the eight questions of the MC2A prompt with the recommendations on 2026-11-14, and on
  starting the work asked that the plant head field be dropped and that each new fill-in be shared read only with the plant's group)
- **Date:** 2026-11-14
- **Track:** Functional (Management Core, part 2A). Migration V093 (expand only). Three tables, two fields on one shipped object, one approver
  kind, one shared-kernel seam, one ability, one notice kind and one mail template. **No new module edge.** Notes on ADR-0259 where it changes (see
  the end).
- **Related:** ADR-0259 (documents are routed to applications by their number; the options of an application), ADR-0189 and ADR-0188 (publishing, its
  own memory, who sees a template), ADR-0145 to ADR-0153 (the approval engine; ADR-0146 decision 3: approvers are resolved when a step becomes
  active), ADR-0187 (hooks), ADR-0192 and ADR-0258 (fill-ins and the reviewer's window), ADR-0115 (one place decides who sees a record), ADR-0154
  (notices are facts, never words), ADR-0003 (row level security), ADR-0012 (what is never logged)

## Context

After the chain of Document Management ends (Plant QMS publishes the document to the creator's group) the creator of a Management Core document
makes it available to the plants. A plant is a location; the people who receive it are the plant heads of that plant. For each plant the creator
chooses who reviews its fill-ins: people and groups, several, different from plant to plant and from document to document. The plant heads fill the
form every month; the reviewers of that plant, for that document, review it. The decisions here are the ones the earlier ADRs leave open.

## Decisions

### 1. The act is the creator's own, apart from Document Management's publish and with a memory of its own

"Publish to plants" is not an approval and not the Publisher's publish. Who may do it is asked in one class (`PlantPublishRule`), by the view and by
every act: the member must see the document by the record store's answer (as for every act on a template), the document must carry an application
that publishes to places (`publishTo: places`) and that the organization has, and the member either **made the document** (owns it) or holds the new
ability `sheets.publish-plants`. The Organization administrator holds every ability, so they are covered; no shipped policy gives it to anybody
else. It is a different thing from `sheets.publish` and the Publisher policy does not carry it. Whether a Revision is in use and whether the document
is retired are facts about the document, said in words by the act.

The access is the same as ever: a read share of the document's record to the group of plant heads an administrator keeps for the plant (the
Department group record for the plant and the fixed department `PLANTHEAD`, resolved by the same `DepartmentAudiences` as every publish, so a plant
with no group, several, a removed one or one with no members is refused and every plant is judged before anything is shared). The memory is the new
table `inspection_plant_publication` (which shares the act made itself, a revoked state like ADR-0188's table) and `inspection_plant_reviewer` (the
person or group chosen for a plant). Both are apart from Document Management's `inspection_publication`: **Document Management's unpublish, a
re-publish and a retire do not touch the plants, and taking a plant back never touches Document Management's shares.** The two acts take the one lock
of the document, and neither takes back a share the other still holds (a live row of either table for the group); an extra check in each removal
asks the other store. The one case this cannot cover is a share that Document Management made for a group the plant also uses, which the plant then
finds already made: Document Management's unpublish keeps it while the plant needs it, and taking the plant back later leaves it, because the plant did
not make it. That is a leftover read share, never a lost one, and only a misconfiguration (one group for both) can cause it.

One act sets the whole (`PUT /templates/{id}/plants`): a plant listed and not yet published to is added; a plant listed with other reviewers has them
changed; a plant published to and not listed is taken back, with its reviewers; an empty list takes the document back from every plant. At least one
reviewer for each plant, each an active member or an existing public group, none twice. The fill-ins that exist stay as they are when a plant is
taken back; a draft at that plant cannot be sent again until the plant is given the document again (its step has nobody to ask).

**Retire and Unpublish in Document Management do not withdraw the plants.** A retired document starts no new fill-in (the rule of ADR-0192 already);
plant heads keep the read share and the fill-ins that exist are untouched. Taking the plants back is the creator's own act. **The plants and their
reviewers belong to the document**, not to a Revision, so a new Revision (a change request published, ADR-0202) keeps them.

### 2. Telling the plant heads: one notice kind, one mail template, facts only

When a plant is added the active members of its group of plant heads (nested groups included, through the new `GroupMembers.usersOf`, filtered by the
directory of users to the active ones) are told: the notice kind `DOCUMENT_AT_PLANT` (category Documents, so each person switches it on or off for
the in-app notice and the mail separately, as for ADR-0259's kind) and the mail template of the same name. Facts: an event key and the address
parts of the page that opens the document; no title, no number and no plant name. The key is the publication row, so the same act again tells nobody
twice and a plant taken back and given again tells again; nobody is told of their own act. The migration widens the check of the notice kinds.

### 3. The approver kind `PUBLICATION_REVIEWERS`: resolved when the step becomes active, through a seam

The kind takes no value. When a step that has it becomes active the approval module asks the shared-kernel contract `PublicationReviewers` who was
chosen for **this record**; the cluster implements it (`PlantReviewers`: the fill-in's header names its document and its plant, written once by the
platform, and the live reviewers of that pair are the answer). The answer is people and groups; the approval module expands each group to its
members **at that moment**, with the code it has for the kind `GROUP`, so a person who joins the group later is not added to a step already active
(ADR-0146 decision 3), and a person named twice is asked once. Everything else is the engine's: only active members who hold the licence their profile
needs are asked (an unlicensed reviewer is left out and the engine records why), the person who sent the fill-in is left out unless the process allows
it, a step with nobody refuses the send in the engine's fixed words unless the step is marked to be skipped, "any one" or "all" is the step's own
setting (one for the process). **A change of reviewers never reaches a request already running**: its tasks were made when its step became active.

Module rules: the contract lives in the shared kernel beside `ApprovalHooks`; `approval` asks it and the cluster answers it, nothing else names it
(an architecture test) and `approval` never depends on the cluster. The browser holds no list: the kind reaches the process builder through the
catalogue (`GET /api/v1/metadata/approvals/catalogue`) with the words the server gives it. The kind is additive: a process stored without it reads
and writes as it did (a codec test). No process is made per publication: one fill-in process of the application serves every document, chosen by the
engine's entry criteria on the field `application` (ADR-0259).

A reviewer reads one fill-in the way any person with an open task does (ADR-0189: the approval grants no access to the record, the open task opens
that one fill-in). **A reviewer of another plant is asked nothing and reads nothing, through the list, a count, a search, the report, the inbox, the
fill-in itself and a notice** (tests). A person who also holds a policy that views every fill-in sees every fill-in: the reviewers a creator chooses
should hold no such policy, and the manual steps say so.

### 4. The monthly fill-in: plant and month on the header, one for a document, a plant and a month

An application asks for it with the option `monthlyFillIn` in its solution file. The shipped fill-in object gets two platform-written, indexed
fields, `plant` (the location's code) and `period` (`YYYY-MM`), written once by the platform when the fill-in is started and never changed; empty for
every other fill-in. **There is no plant head field**: the owner of the header is the person who started it and the sheets module already keeps who
started it, so a third copy could only drift. Every shipped policy that lists fields of the fill-in lists the two new ones (a field a policy does not
list does not exist for the person), and a no-database test keeps it so. The list and the report read the two fields only when the fill-in object
has them, so the code shared by every application never asks an object for a field it lacks.

Who may start one: the person must hold the right to start fill-ins (as ever), be plant head of a plant (a member of the group the mapping names for
the plant and `PLANTHEAD`) **and** the document must be published to that plant. A person of one plant gets it without choosing; a person of several
chooses. The month defaults to this month; an earlier month is allowed (catching up); a month that has not begun is refused. **"This month" is the
month of the clock in UTC**, the same for everybody; a person in a zone ahead of UTC can start the new month a few hours after they see it begin. An
application that does not ask for monthly fill-ins refuses a plant and a month.

**One fill-in for a document, a plant and a month.** The record store's uniqueness cannot say it (it holds one field or one pair of links), so the claim
is its own table, `inspection_fill_period`: one row for each monthly fill-in, written in the transaction that makes the header, with a unique index on
(organization, document, plant, month) and one on the fill-in. It holds only fill-ins that have a plant, so the fill-ins of every other application
are untouched. The application looks first and answers in fixed words that tell no identifier and no name; the index is the backstop when two start at
the same moment, and its violation undoes the whole start with the same words. A claim never changes and is never deleted, so a fill-in that is
rejected is returned and edited, and the month is never made again.

**The plant mates read it.** At the start the new header is shared read only with the plant's group of plant heads (the group of the publication
row), so a second plant head of the plant sees that the month exists and can open it. Only the person who started it changes the cells (the rule of
ADR-0192 is untouched). The share is never removed, because a fill-in is never deleted; taking the plant back does not take the fill-ins away from the
people who had them.

### 5. What is never written down

A plant code, a month a person typed, a reviewer or a group chosen, a group's name: never a log, an audit record, an event, a notice, a mail subject, a
metric or an error. The audit says the document's identifier and counts (`inspection.template.plants.published`: plants, plants added and withdrawn,
shares made and ended, reviewers added and removed, people told); the start of a fill-in adds only whether it was monthly. A refusal names the
place of the request (`plants[1]`, `plants[1].reviewers[0]`) and never the value.

## Consequences

- One generic seam and one approver kind serve the next applications; the Management Core files and the demo script are the only data. Nothing in the
  engine, the record store or the sheets module names Management Core.
- The score, the 85 percent rule, the Action plan and top management's role in them are MC2B; the dashboard is MC3.
- Not built, on purpose: a reminder to plant heads who have not filled their month (the timers of the approval module could carry it later), an
  approval of the plant list before it reaches the plants (a different design that would use the engine's choice step, ADR-0200), narrowing the
  approvals inbox to an application.
- Costs: three small statements on the start of a monthly fill-in; one statement for the reviewers when a step becomes active; nothing on the read
  path of any other fill-in.

## Notes on earlier decisions

- **ADR-0259:** its options (`publishTo: places`, `publishDepartment`) were "declared and ignored by Document Management's publish" until this
  decision; they are now read by the creator's act and still ignored by Document Management's publish (`RecordSet.withoutPublishOptions`). The
  option `monthlyFillIn` is new. The field `application` is unchanged.
- **ADR-0189:** the open-task read now also covers a person who holds a task through `PUBLICATION_REVIEWERS`; nothing changed in how it is decided.
- **ADR-0146:** one more approver kind; the resolution of a step is otherwise as before.

## Note added by ADR-0261 (MC2B)

- The reviewers of a fill-in are the ones chosen for its document and plant. For an **Action plan** (a fill-in whose `parentFill` names another) they are
  those of the fill-in it answers, read through that link by `PlantReviewers`; the choice made for the Action plan form is never used.
- The process of Management Core fill-ins made by the demo setup carries the entry criteria `application = 'management-core' AND ISBLANK(parentFill)`,
  and a second process for Action plans carries `... AND NOT(ISBLANK(parentFill))`: the engine refuses two processes that both fit.
- "What top management decides in the score" (the last step of the demo process) is answered by ADR-0261 decisions 5 and 6.
