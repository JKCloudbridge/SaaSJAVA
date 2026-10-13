# ADR-0100: The query model and its stages

- **Status:** Accepted
- **Date:** 2026-10-12
- **Sprint:** S16
- **Related:** [ADR-0084](0084-the-record-store.md), [ADR-0089](0089-what-a-record-may-write-and-what-it-may-never-leak.md), [ADR-0092](0092-field-security-on-writes-and-reads.md), [ADR-0095](0095-formulas-stored-on-write.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0103](0103-indexed-and-unindexed-paths-answer-alike.md), [ADR-0104](0104-keyset-paging-with-records-that-have-no-value.md), [ADR-0105](0105-filters-and-orders-without-an-index-on-large-objects.md)

## Context

Until Sprint 16 a record could be read by identifier or listed in identifier order. A CRM is mostly filtered, sorted lists. The architecture notes (note 4, sections 23 and 24) fix the rule: a query is a **controlled model**, never SQL from a person, taken
through fixed steps: query model, validation against the metadata, field security, authorization constraints, plan, parameterized SQL, execution.

## Decision

1. **A closed model** (`RecordQuery` in the `data` root): a filter tree (`AND`, `OR`, and comparisons `EQ NE GT GTE LT LTE BETWEEN IN IS_EMPTY IS_NOT_EMPTY STARTS_WITH CONTAINS`), one or two sort keys, a projection of fields, lookups to follow
   (ADR-0107), a page size and a cursor. A field is named by its API name; a value is data. No operator takes an expression, a function name or a path. The HTTP form is `POST /api/v1/objects/{object}/records/query` (a tree does not fit a URL);
   `GET .../records` stays as the simple list and is **the same code with no filter and no order**.
2. **The stages, in this order, and the first stage that finds problems answers alone** with all its problems and their places: *shape* (depth 4, 50 nodes, 100 values in an `IN`, the right number of values per comparison, page 1 to 200), *fields*,
   *operators and values*, *record security*, *plan*, *statement*, *execution* (`QueryAnalyzer.STAGES`; a test holds the service to the list).
3. **A field the member may not read is a field that does not exist.** Names are resolved against the metadata and against the member's field permissions **in one step, before anything that depends on the type of the field is looked at**. If the
   order were metadata, then operators, then permissions, an error such as "this comparison does not fit a number" would tell the member that a field they may not read exists and what type it is, and a filter would let them infer its values. The
   same applies to sort keys, the projection and the lookups. Records returned leave out the fields the member may not read, with the same function as the single read.
4. **Text is compared exactly** (case-sensitive, as `=` is everywhere on the platform, ADR-0095); no comparison applies a function to the column that would defeat an index. `STARTS_WITH` and `CONTAINS` are scans by nature and use `starts_with` and
   `position`, never a pattern, so a `%` or `_` typed by a person is only text.
5. **Names reach SQL only as parameters.** The storage builds the statement from the plan (`JsonbQuerySql`) out of fixed fragments: comparison kinds, slot columns from `SlotColumn` (a closed enum), system columns, casts. Field API names are bound
   (`cast(:f0 as text)`), values are bound (`cast(:p3 as numeric)`). Only `JsonbRecordStorage` names the table (a test scans the sources). A corpus of hostile queries (`UnsafeQueryCorpusTest`) fails the build if one gets through.
6. **No total.** A total is a count, which is a scan (ADR-0105). A page says whether another follows and gives a cursor that is good only for the same query (ADR-0104). An approximate count for the administrators' screens can be decided with the
   list screens (Sprints 19 and 20).
7. **The seam for record-level security** is `RecordVisibility.constraintFor(passed, access)`, asked by the list, the query and the single read alike, after the fields and operators are checked and before the plan, which adds the answer to the
   filter. Sprint 17 fills it; until then it answers "no extra condition". A test holds all three readers to calling it.
8. **A formula field can be used in a filter or an order since Sprint 16A**, while its stored results are current for the object's formulas ([ADR-0114](0114-formula-fields-in-filters-and-orders.md)); until then it was refused (ADR-0095: the stored result is not current for records nobody has saved since the formula changed).

## Consequences

- The answer to a query is as safe as the single read: the same gate, the same field filter, the same "not found" for an object the member may not see.
- Long text, multi-select picklists and times of day cannot be filtered or sorted (stated in the refusal); a link cannot be sorted.
- The cursor carries the sort values of the last record (fields the member could read, since they were sort keys) and a short fingerprint of the query; it is not signed (a made-up cursor only moves the caller within what they may read).
