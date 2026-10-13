-- The "indexed" flag of a field (Sprint 16, ADR-0100 and ADR-0101).
--
-- An administrator flags a field "indexed" to say: people will filter or sort records by it, make that fast. This
-- column only records the intent. Which physical column ("slot") of the record table holds the copy, and whether the
-- copy is complete yet, is state of the index policy in the data module (V041), never read from here.
--
-- Expand migration (ADR-0009): a new column with a default, nothing is rewritten that a running instance of the
-- previous release reads. The previous release ignores it; every field is "not indexed" until someone flags it.

alter table field_definition add column is_indexed boolean not null default false;

comment on column field_definition.is_indexed is
    'Intent: the organization wants records filtered or sorted by this field to be fast. The slot and its state are in field_slot (ADR-0101).';
