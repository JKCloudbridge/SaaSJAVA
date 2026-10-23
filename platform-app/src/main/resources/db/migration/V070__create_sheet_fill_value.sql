-- The reportable values of fill-ins (Functional track FF2, ADR-0193). Expand only; nothing is removed or renamed.
--
-- A template author can flag columns of a version "reportable". Every save of a fill-in copies the values of those
-- columns, in the transaction of the save, into this table: one row for each non-empty reportable cell, with the value
-- in a typed column (text, number, date or yes/no). A report ("the fill-ins where the Safety Status is Fail") reads
-- this table and never the cells document: with forced row level security PostgreSQL cannot use an index on the JSON
-- operators (ADR-0083, ADR-0101), so the values are plain typed columns in ordinary B-tree indexes, exactly as search
-- words are (V049).
--
-- The table is a derived copy. The cells of sheet_fill stay the truth; a save replaces the copy of its fill-in as a
-- whole, so the two cannot drift apart, and the copy is never written by anything but the save of the same
-- transaction. It holds values of the organization, so it is never written to a log, an audit record or an event, and
-- a message about it names no value.
--
-- The copy outlives the archive of the cells (V071): an archived fill-in is still found by a report.
--
--   sheet_fill_value  One row for each (fill-in, row, column) whose column is reportable in the pinned version and
--                     whose cell holds a value. The sheet and version are repeated here so a report is answered from
--                     this table alone; they cannot change (the fill-in's pin is immutable, V069).

create table sheet_fill_value (
    id             uuid        primary key default uuidv7(),
    tenant_id      uuid        not null default platform_current_tenant() references tenant (id),
    fill_object    text        not null,
    fill_id        uuid        not null,
    owner_object   text        not null,
    owner_id       uuid        not null,
    version_no     integer     not null,
    row_id         text        not null,
    column_id      text        not null,
    kind           text        not null,
    text_value     text collate "C",
    number_value   numeric,
    date_value     date,
    boolean_value  boolean,
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    created_by     uuid        not null,
    updated_at     timestamptz not null default now(),
    updated_by     uuid        not null,
    deleted_at     timestamptz,
    deleted_by     uuid,
    constraint sheet_fill_value_object_shape check (fill_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'
        and owner_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint sheet_fill_value_version_range check (version_no between 1 and 100000),
    constraint sheet_fill_value_kind_known check (kind in ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN')),
    -- Exactly the column of the kind holds the value.
    constraint sheet_fill_value_one_value check (
        (kind = 'TEXT' and text_value is not null and number_value is null and date_value is null
            and boolean_value is null)
        or (kind = 'NUMBER' and number_value is not null and text_value is null and date_value is null
            and boolean_value is null)
        or (kind = 'DATE' and date_value is not null and text_value is null and number_value is null
            and boolean_value is null)
        or (kind = 'BOOLEAN' and boolean_value is not null and text_value is null and number_value is null
            and date_value is null)),
    constraint sheet_fill_value_text_length check (text_value is null or char_length(text_value) <= 10000)
);

-- The copy of one fill-in: to replace it as a whole, and the key of a value.
create unique index sheet_fill_value_cell on sheet_fill_value (tenant_id, fill_object, fill_id, row_id, column_id);

-- The four ways a report asks: the sheet and version, the column, then the value, then the fill-in (so the fill-ins of a
-- value come in order and can be paged by key). One partial index for each kind, so a row costs one entry of them.
create index sheet_fill_value_text on sheet_fill_value
    (tenant_id, owner_object, owner_id, version_no, column_id, text_value, fill_id) where kind = 'TEXT';
create index sheet_fill_value_number on sheet_fill_value
    (tenant_id, owner_object, owner_id, version_no, column_id, number_value, fill_id) where kind = 'NUMBER';
create index sheet_fill_value_date on sheet_fill_value
    (tenant_id, owner_object, owner_id, version_no, column_id, date_value, fill_id) where kind = 'DATE';
create index sheet_fill_value_boolean on sheet_fill_value
    (tenant_id, owner_object, owner_id, version_no, column_id, boolean_value, fill_id) where kind = 'BOOLEAN';

create trigger sheet_fill_value_row_guard before insert or update on sheet_fill_value
    for each row execute function platform_row_guard();
create trigger sheet_fill_value_tenant_guard before update on sheet_fill_value
    for each row execute function platform_tenant_guard();

alter table sheet_fill_value enable row level security;
alter table sheet_fill_value force row level security;
create policy sheet_fill_value_insert on sheet_fill_value for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy sheet_fill_value_select on sheet_fill_value for select
    using (tenant_id = (select platform_current_tenant()));
create policy sheet_fill_value_update on sheet_fill_value for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy sheet_fill_value_delete on sheet_fill_value for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table sheet_fill_value is
    'The values of the reportable columns of a fill-in, one typed row for each non-empty cell, replaced by every save of the fill-in in its transaction. Tenant-scoped, row level security forced (ADR-0193).';
