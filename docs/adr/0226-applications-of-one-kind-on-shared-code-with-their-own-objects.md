# ADR-0226: Applications of one kind on shared code, each with its own objects

- **Status:** Accepted
- **Date:** 2026-10-31
- **Track:** Functional (FA13B). One migration (V087), one new shared-kernel contract, three contracts widened, a required `application` parameter on the
  endpoints of the cluster. The generic code holds no word of any one solution (`SolutionBoundaryTest`); no new module edge.
- **Related:** ADR-0181 (the solution framework; amended here), ADR-0203 (the grid and the dashboard; amended here), ADR-0225 (sharing objects between
  bundles; amended here), ADR-0187 (approval hooks; widened), ADR-0193 (file rules; widened), ADR-0115 (who sees a record: no second rule),
  ADR-0184 (number series), ADR-0012 (what is never logged)

## Context

The business wants a tile, **Environment Management**, with the same functions as the Quality Management System (templates and their sheets,
approval, numbering, publishing to departments, revisions through change requests, fill-ins, the report, the dashboard) but over **its own records**:
what is made there must never be listed in the Quality Management System, and the reverse. More applications of the kind will follow, and adding one
must cost **data files only**, with no Java and no web code.

The platform ties approval processes, number series, access policies, sharing, record locks and kept records to the **object**. The cluster
module (`inspection`) named three objects as constants (`Templates.OBJECT`, `Fills.OBJECT`, `RequestRecords.OBJECT`), a series (`document`) and an
application (`inspection`) in about 135 places in 30 classes and in the web pages.

## Decision

**One code base, a second set of objects, chosen by data.** (Rejected: one set of objects with an application tag, because approval, numbering
and access policies belong to the object and both tiles would share them; a copy of about 60 classes and the screens, because every later fix would
be made twice.)

1. **The parts are declared in the solution file.** A bundle may carry `roles:`, a mapping of the name of a part to the object that plays it
   (`template`, `fill`, `changeRequest` for this kind). The object must be one the bundle brings or uses; an object plays one part. The generic
   loader carries the names and knows none. Two bundles may declare the very same parts (Document Management shows the Quality Management System's
   records); an object may not be claimed in another part or in another combination (`SolutionValidator.checkRoles`).
2. **A shared-kernel contract hands them to the cluster** (`ObjectRoleDeclarations`, `ObjectRoles`), implemented by the solution module
   (`ShippedObjectRoles`) the way `SheetOwnerDeclarations` and `NumberSeriesDeclarations` are. `available(application)` also asks the plan feature, at
   the moment of the call and never stored.
3. **The cluster asks one place which set an application means** (`RecordSets`, `RecordSet`): the objects and the number series of the template
   object (found in the numbering declarations, so no further key). A mistake stops the start: an application that leaves out a part, a template
   object with not exactly one series, an object in two sets.
4. **A request names its application; it proves nothing.** Every endpoint of the template, fill-in, change-request and dashboard controllers takes a
   required `application` query parameter. `RecordSets.require` answers NOT_FOUND, in one set of words, for an application that is unknown, one the
   organization does not have (its plan feature is off), and a request with no member (the platform address). An identifier of another set is an
   unknown one, because the existence check is asked of the set's own object. What the member may do inside the set is decided, as ever, by the
   record store and the decision API.
5. **Registries serve several objects.** `ApprovalHooks`, `RecordFileRules`, `NoticeDestination` and `RecordWriteRule` gain a default method
   (`objects()` or `serves(object)`); a registry indexes every object a bean lists, and two beans for one object still stop the start. The calls name
   the object (`RecordFileRules.Change.object`, `RecordWriteRule.prepare(object, ...)`, `NoticeDestination.readablePath(object, ...)`), so one bean serves
   every set and takes the set from the object it is called with.
6. **Two tables read across templates carry the set** (V087): `inspection_change_request` and `inspection_template_event` get `template_object`
   (default: the object of the only set that existed), written by the application and never changed (the guard of V075 is replaced to know it). The list
   across templates, the requests to act on and the timeline of the home page read one set only, so one set's rows can neither show nor fill another's
   page or bound. Every other table of the cluster is keyed by a record identifier and is separate by construction.
7. **The browser names no object and no application.** The API lists the parts with each application (`objectRoles` on the member's applications);
   the pages read their object names there (`useRecordObjects`) and name their application in a query parameter (`scope.ts`, set when an application page
   is drawn). A page is found by its key among the registered pages, so an application of a kind already registered needs no web code. The forms
   that ask for a choice are found by the part an object plays (`choices.ts`).
8. **Access policies of the new application have names of their own** (`Environment Author`, ...). Holders of the other application's policies get
   nothing in it. The department setup (`DepartmentGroup` and its policy) is shared by the three applications that publish to departments, through the
   rule of ADR-0225. The objects of the new application are kept for good (`retainRecords`) with the same database guards.
9. **Proof it is data only:** a throwaway third application made only of files (objects, policies, numbering, bundle) runs the whole lifecycle for its
   own records with no code change (`ThirdApplicationIT`, with its files on the classpath of that test alone).

## Consequences

- The cost of the next application of the kind is a folder: a bundle, three object files, a numbering file. The three object files and the policy
  blocks are copies of the other application's data on purpose (a later change to the fields is made in each), and `EnvironmentParityTest` fails the build
  when the fields the functions read drift apart.
- Every endpoint of the cluster needs `application`; the generated client changed (a major edit of the web calls, no change of rights).
- `RecordSets` is the one place that knows the parts; the architecture test `noClassOfTheClusterNamesTheObjectOfAnApplicationAsAConstant` keeps it so.
- A forged application name changes nothing: it chooses among sets and the same rights apply inside the chosen one.
- Left out on purpose: moving records between applications, a screen to create an application, a per-application licence, settings for an imagined
  third application.
