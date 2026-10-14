-- Search over the records of one object (Sprint 17, ADR-0124).
--
-- The measurement that decided the design (1.3 million records, row level security forced, PostgreSQL 18): a full-text
-- index (tsvector with a GIN index) is not used by the planner for the application's role, because the match operator
-- is not "leakproof" (a function that cannot reveal the rows it is asked about), and with forced row level security
-- PostgreSQL does not let an operator that is not leakproof use an index before the policy has run. The ranked search
-- became a scan of the whole table (226 ms and growing with the table). The comparison of text with =, >= and < is
-- leakproof, so the words of a record are kept as plain rows in a B-tree: one row for each word of each searchable
-- field of each record. The same search then took 4 ms for a rare word and 98 ms for a word that a tenth of the
-- records contain.
--
--   search_term    One row per word of a searchable field of a record. The word is compared byte by byte (collation
--                  "C"), which also makes "starts with" a range. A record's words are kept by the write that changes
--                  the field, in its transaction (the same discipline as the typed copies of Sprint 16); a record
--                  removed for good takes its words (the foreign key); a record in the recycle bin keeps them and the
--                  search leaves it out.
--   search_field   Whether the words of a searchable field are complete for the records that existed before the flag:
--                  FILLING (a background job writes them) or FILLED. A field is searched only when FILLED.

create table search_term (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    record_id       uuid        not null references object_record (id) on delete cascade,
    field_api_name  text        not null,
    term            text collate "C" not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint search_term_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint search_term_field_name_shape check (field_api_name ~ '^[a-z][A-Za-z0-9_]{0,55}__c$'
        or field_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint search_term_term_length check (char_length(term) between 1 and 64)
);

-- The index every search reads: the object, the word, then the record (so the records of a word come in order and
-- two words can be matched by a join of two sorted lists). The field is part of the entry so the field can be
-- judged without reading the row.
create index search_term_lookup on search_term (tenant_id, object_api_name, term, record_id) include (field_api_name);

-- The words of one field of one record, to replace them and to find them.
create unique index search_term_record_field_term on search_term (tenant_id, record_id, field_api_name, term)
    where deleted_at is null;

-- The cascade of a removal for good finds the words of a record by the record alone.
create index search_term_record on search_term (record_id);

create trigger search_term_row_guard before insert or update on search_term
    for each row execute function platform_row_guard();
create trigger search_term_tenant_guard before update on search_term
    for each row execute function platform_tenant_guard();

alter table search_term enable row level security;
alter table search_term force row level security;
create policy search_term_insert on search_term for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy search_term_select on search_term for select
    using (tenant_id = (select platform_current_tenant()));
create policy search_term_update on search_term for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy search_term_delete on search_term for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table search_term is
    'A word of a searchable field of a record, compared byte by byte. Tenant-scoped, row level security forced (ADR-0124).';

-- ---------------------------------------------------------------------------------------------------------------
-- search_field
-- ---------------------------------------------------------------------------------------------------------------

create table search_field (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    field_api_name  text        not null,
    state           text        not null default 'FILLING',
    cursor_id       uuid,
    records_done    bigint      not null default 0,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint search_field_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint search_field_state_known check (state in ('FILLING', 'FILLED')),
    constraint search_field_progress_positive check (records_done >= 0)
);

create unique index search_field_tenant_field on search_field (tenant_id, object_api_name, field_api_name)
    where deleted_at is null;

create trigger search_field_row_guard before insert or update on search_field
    for each row execute function platform_row_guard();
create trigger search_field_tenant_guard before update on search_field
    for each row execute function platform_tenant_guard();

alter table search_field enable row level security;
alter table search_field force row level security;
create policy search_field_insert on search_field for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy search_field_select on search_field for select
    using (tenant_id = (select platform_current_tenant()));
create policy search_field_update on search_field for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy search_field_delete on search_field for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table search_field is
    'Whether the words of a searchable field are complete for the records that existed before the flag. Tenant-scoped, row level security forced (ADR-0124).';
