-- Two flags a field can carry (Sprint 17, ADR-0123, ADR-0124).
--
--   is_external_id   The field holds the identifier the record has in another system, so that an import can find the
--                    record by it with one indexed read (upsert) and can name a record of another object by it. The
--                    flag requires the field to be unique (the uniqueness machinery of Sprint 16A enforces it and gives
--                    the field its typed copy and index); the database refuses the combination the services refuse.
--   is_searchable    The search of the object covers the field. The services allow a few per object; the search table
--                    keeps the words of these fields only.
--
-- Both are expand-only: every existing field has both off, so nothing changes until an administrator flags one.

alter table field_definition
    add column is_external_id boolean not null default false,
    add column is_searchable  boolean not null default false;

alter table field_definition
    add constraint field_definition_external_id_is_unique check (not is_external_id or is_unique);

comment on column field_definition.is_external_id is
    'The field holds the identifier the record has in another system; an import upserts by it. Requires is_unique (ADR-0123).';
comment on column field_definition.is_searchable is
    'The search of the object covers the field (ADR-0124).';
