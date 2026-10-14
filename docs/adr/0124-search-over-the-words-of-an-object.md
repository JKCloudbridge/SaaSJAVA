# ADR-0124: Search over the words of an object

- **Status:** Accepted
- **Date:** 2026-10-13
- **Sprint:** S17
- **Related:** [ADR-0100](0100-the-query-model-and-its-stages.md), [ADR-0101](0101-the-index-policy-and-the-slots.md), [ADR-0115](0115-the-record-access-model-and-how-it-reaches-a-statement.md), [ADR-0082](0082-storage-benchmark-specification-gate-g1.md), [ADR-0003](0003-rls-defence-in-depth.md)

## Context

People search a CRM by typing a name, a company, part of an address. The query engine (Sprint 16) filters by exact and range conditions on typed fields; it has no notion of "a record that has these words". The obvious tool, PostgreSQL's full-text search with an inverted (GIN) index, was **measured first** on 1.3 million records under forced row level security, the way the platform runs:

- the full-text operators are not "leakproof", so with row level security forced the planner cannot use a GIN index on them: the search became a parallel sequential scan, 226 ms at 1.3 million records and growing linearly, and the ranking made it worse;
- an **inverted index made of ordinary B-tree rows** (one row per record, field and word) was read through the indexes: a rare word in 3.9 ms, two words (the rarer first, the other checked as a semi-join) in 4.2 ms, a word on 10 percent of the records in 98 ms.

Search must be fast in the first case, correct in the second (it is part of the same security), and cheap to keep.

## Decision

1. **The words are rows.** `search_term (tenant, object, record, field, term)` (V049) with a unique key `(tenant, record, field, term)` and the lookup index `(tenant, object, term, record) include (field)`; the term is compared in the binary collation, so a prefix is a range (`term >= :w and term < :w || max`) and the index serves both a whole word and a beginning. The table is tenant-scoped, forced RLS, like every
   other (ADR-0003); a word is typed text of the organization: it is a parameter, never logged, audited or announced.
2. **One function makes the words, for both sides.** `SearchTokens` turns a stored value and a typed search into words, so they always agree: accents dropped (`José` is found by `jose`), lower case, runs of letters and digits, at least two characters (a single digit is kept) and at most 40, at most 64 words a value; an e-mail address is kept whole as well; a telephone number is also kept as its digits run together.
3. **Which fields are searched is a flag of the field,** `searchable` (V047), allowed on text, long text, e-mail, phone and picklist fields, at most four per object (`platform.metadata.limits.max-searchable-fields-per-object`). Changing the flag follows the machinery of indexed fields (ADR-0101): intent in the field, state in `search_field` (`FILLING`, `FILLED`); `SearchBackfill` fills the words of existing records in batches in the organization's own context and,
   when the flag is removed, removes the words. **A field is searched only when its words are complete**; the object's index status says so (`search` entries: `WAITING`, `FILLING`, `COMPLETE`).
4. **The words follow every write, in the transaction of the write** (`SearchIndexer`, called wherever the typed copies and the links are kept): a write that names no searchable field costs nothing; a change of a value removes the words it no longer has and adds the new ones; a record removed for good takes its words with it (cascade); a record in the recycle bin keeps them but is not found (the statement checks that the record is alive).
5. **A search finds the records that have every word,** the last word counting as a beginning (the person is still typing); at most five words are looked at. The engine asks for each word how many records have it, reading at most `platform.data.search.max-candidates` (50,000) index entries, reads the **rarest word first** and checks the others against its records, so the cost is about the rarest word's records. A search all of whose words are more common than the bound is refused
   with a clear message ("type more"). The best matches come first (a match in the first searchable field counts most, then the others), then by identifier, in pages with a cursor that is good only for the same text.
6. **Search is part of the same security.** The fields searched are the searchable fields **the member may read** (a record never matches on a field the member cannot see, so a search cannot be used to learn what a field holds; a member who can read none of them is told there is nothing to search); the records are those the member may reach (`RecordVisibility`, the same condition as a list, in the same statement); what comes back is shaped and filtered
   exactly like the answer of a query (`FieldSecurity`). Nothing is highlighted, because a highlight would repeat a value.
7. **Not built, on purpose:** search across objects (it needs a different ranking and its own limits), typo tolerance, stemming and synonyms, search inside files. Each can be added on top of the same table; none is promised by this ADR.
8. **The measurement stands in for a failure test.** The benchmark has a search scenario with the thresholds set before the numbers were seen (ADR-0082): if the search over a large object does not meet them, the sprint stops and asks (the instruction of the sprint), it is not tuned away.

## Consequences

- A write of a searchable field costs one delete and a few inserts of small rows in the same transaction; the fields are few (four) and the words are bounded (64).
- The index is as large as the words it holds; only fields an administrator chooses are in it.
- Because the table is ordinary rows, it is backed up, replicated, isolated and removed with the organization like everything else, and no extension is needed.
