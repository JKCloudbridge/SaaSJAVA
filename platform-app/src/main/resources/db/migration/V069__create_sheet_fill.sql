-- The cells of a fill-in (Functional track FF1, ADR-0192). Expand only; nothing is removed or renamed.
--
--   sheet_fill  One row per fill-in. A fill-in is what a person types into a sheet that was published: it belongs to a
--               header record of its own (the fill object and id; the record store gives it its list, owner, shares and
--               approval) and is pinned for ever to the version of the sheet it was started on (the owner object and id
--               of the sheet and the version number). The row holds the cells the person typed as one document, the
--               status of the fill-in, and what the database needs to keep the rules below. The module that keeps it
--               knows no business word.
--
-- Why a table of its own: the cells are up to a megabyte of nested JSON, which a record of the generic store (flat
-- values, 256 KB) cannot hold, and a fill-in must show against the version it was made on whatever happens to the sheet
-- afterwards. Like a version, the document is jsonb (compressed by PostgreSQL), there is no index on it, and a list of
-- fill-ins never selects it (ADR-0083, ADR-0173 section 6). The document is shape B of that section: a list of rows,
-- each with its cells keyed by the stable identifier of the column.
--
-- What must hold even if the application had a bug (proved by direct statements in SheetFillGuardIT):
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * a fill-in is started only on a version that is in use at that moment (acceptance criterion 1), and it keeps that
--     version and its sheet for ever: nothing about the pin, the header or the organization can change;
--   * there is one fill row for each header record;
--   * the cells change only while the fill-in is a draft or has been returned, with one exception: the narrow, counted
--     correction by a person who may edit after submit, which only a transaction that sets platform.fill_correction = on
--     may make (the pattern of platform.review_edit, V064), and which raises the count of corrections by exactly one;
--   * the status moves only along draft to submitted; returned to submitted; submitted to approved, returned or back to
--     draft (a recall); and a status never moves together with a change of the cells;
--   * no fill-in is ever deleted or soft-deleted (decision 10 of the business analysis). A revoked DELETE grant would
--     not hold, because the manual migration M001 grants it again on every run, so the protection is a trigger.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. The cells are text of the organization, so they are never written to a log, an audit record or an event.

create table sheet_fill (
    id                   uuid        primary key default uuidv7(),
    tenant_id            uuid        not null default platform_current_tenant() references tenant (id),
    fill_object          text        not null,
    fill_id              uuid        not null,
    owner_object         text        not null,
    owner_id             uuid        not null,
    version_no           integer     not null,
    status               text        not null default 'DRAFT',
    cells                jsonb       not null,
    cells_hash           bytea       not null,
    cells_bytes          integer     not null,
    row_count            integer     not null default 0,
    corrections          integer     not null default 0,
    submitted_at         timestamptz,
    decided_at           timestamptz,
    approval_request_id  uuid,
    version              bigint      not null default 0,
    created_at           timestamptz not null default now(),
    created_by           uuid        not null,
    updated_at           timestamptz not null default now(),
    updated_by           uuid        not null,
    deleted_at           timestamptz,
    deleted_by           uuid,
    constraint sheet_fill_object_shape check (fill_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'
        and owner_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint sheet_fill_version_range check (version_no between 1 and 100000),
    constraint sheet_fill_status_known check (status in ('DRAFT', 'SUBMITTED', 'RETURNED', 'APPROVED')),
    constraint sheet_fill_cells_is_object check (jsonb_typeof(cells) = 'object'),
    constraint sheet_fill_hash_length check (octet_length(cells_hash) = 32),
    -- The application's own limit (platform.sheets.fills.max-bytes) is lower; this is the ceiling whatever it says.
    constraint sheet_fill_cells_size check (cells_bytes between 2 and 5000000),
    constraint sheet_fill_row_count_range check (row_count between 0 and 10000),
    constraint sheet_fill_corrections_range check (corrections >= 0)
);

-- One fill row for each header record.
create unique index sheet_fill_header
    on sheet_fill (tenant_id, fill_object, fill_id) where deleted_at is null;
-- The fill-ins made from one sheet, newest first: the order the lists of a screen use, and the way to count them.
create index sheet_fill_owner_newest
    on sheet_fill (tenant_id, owner_object, owner_id, created_at desc, id desc) where deleted_at is null;

create function platform_sheet_fill_guard() returns trigger
    language plpgsql
as $guard$
declare
    cells_changed boolean;
begin
    -- Messages name the table only, never a value.
    if tg_op = 'INSERT' then
        if new.status <> 'DRAFT' or new.corrections <> 0 or new.submitted_at is not null
                or new.decided_at is not null or new.approval_request_id is not null then
            raise exception 'fill guard: a fill-in of % starts as a draft', tg_table_name
                using errcode = 'check_violation';
        end if;
        -- Criterion 1: a fill-in is started only on a version in use.
        perform 1 from sheet_version v
            where v.tenant_id = new.tenant_id and v.owner_object = new.owner_object and v.owner_id = new.owner_id
                and v.version_no = new.version_no and v.status = 'ACTIVE' and v.deleted_at is null;
        if not found then
            raise exception 'fill guard: a fill-in of % is started on the version in use', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.tenant_id is distinct from old.tenant_id or new.fill_object is distinct from old.fill_object
            or new.fill_id is distinct from old.fill_id or new.owner_object is distinct from old.owner_object
            or new.owner_id is distinct from old.owner_id or new.version_no is distinct from old.version_no then
        raise exception 'fill guard: the header and the version of a fill-in of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        raise exception 'fill guard: the fill-ins of % are kept', tg_table_name using errcode = 'check_violation';
    end if;

    cells_changed := new.cells is distinct from old.cells or new.cells_hash is distinct from old.cells_hash
        or new.cells_bytes is distinct from old.cells_bytes or new.row_count is distinct from old.row_count;

    if new.status is distinct from old.status then
        if (old.status, new.status) not in (
                ('DRAFT', 'SUBMITTED'), ('RETURNED', 'SUBMITTED'),
                ('SUBMITTED', 'APPROVED'), ('SUBMITTED', 'RETURNED'), ('SUBMITTED', 'DRAFT')) then
            raise exception 'fill guard: this change of status of % is not part of the lifecycle', tg_table_name
                using errcode = 'check_violation';
        end if;
        if cells_changed or new.corrections is distinct from old.corrections then
            raise exception 'fill guard: the status and the cells of % do not change together', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if cells_changed then
        if old.status in ('DRAFT', 'RETURNED') and new.corrections is not distinct from old.corrections then
            null; -- the filler's own work
        elsif old.status in ('SUBMITTED', 'APPROVED')
                and coalesce(current_setting('platform.fill_correction', true), '') = 'on'
                and new.corrections = old.corrections + 1 then
            null; -- the counted correction after submit, see the header of this file
        else
            raise exception 'fill guard: the cells of a fill-in of % that was submitted cannot change', tg_table_name
                using errcode = 'check_violation';
        end if;
    elsif new.corrections is distinct from old.corrections then
        raise exception 'fill guard: only a change of the cells counts as a correction in %', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_sheet_fill_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'fill guard: the fill-ins of % are kept', tg_table_name using errcode = 'check_violation';
end;
$guard$;

create trigger sheet_fill_guard before insert or update on sheet_fill
    for each row execute function platform_sheet_fill_guard();
create trigger sheet_fill_no_delete before delete on sheet_fill
    for each row execute function platform_sheet_fill_no_delete();
create trigger sheet_fill_row_guard before insert or update on sheet_fill
    for each row execute function platform_row_guard();
create trigger sheet_fill_tenant_guard before update on sheet_fill
    for each row execute function platform_tenant_guard();

alter table sheet_fill enable row level security;
alter table sheet_fill force row level security;
create policy sheet_fill_insert on sheet_fill for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy sheet_fill_select on sheet_fill for select
    using (tenant_id = (select platform_current_tenant()));
create policy sheet_fill_update on sheet_fill for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy sheet_fill_delete on sheet_fill for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table sheet_fill is
    'The cells of a fill-in, pinned to the version of the sheet it was started on. Never deleted; the cells change only while the fill-in is a draft or returned, or by the counted correction (ADR-0192).';
comment on function platform_sheet_fill_guard() is
    'Enforces the rules of a fill-in: started on the version in use, an immutable pin, the allowed moves of status, and cells that change only in draft, returned or by the counted correction.';
