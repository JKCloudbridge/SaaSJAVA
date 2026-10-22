-- The versions of a sheet (Functional track FA3, ADR-0173, ADR-0174, ADR-0186). Expand only; nothing is removed or renamed.
--
--   sheet_version  One row per version of a sheet. A sheet is a spreadsheet-like grid that belongs to one record of one
--                  object (the owner); the module that keeps it knows no business word. A version is numbered by the
--                  application (the highest plus one, under a lock on the owner) and carries a status of its own; the
--                  version in use is the one with status ACTIVE, so the owner record holds no pointer to it.
--
-- Why a table of its own: a grid is up to 1 MB of nested JSON, which a record of the generic store (flat values, 256 KB)
-- cannot hold, and a version must stay exactly as it was approved because later fill-ins pin it. The grid is stored as
-- jsonb (compressed by PostgreSQL, about 5 times smaller than the text); there is no index on it, and a list of versions
-- never selects it (ADR-0083, ADR-0173). dimension, rows_mode and grid_bytes are copies of facts of the grid so that a
-- list needs not read it.
--
-- What must hold even if the application had a bug (proved by direct statements in SheetVersionGuardIT):
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * a version number is never reused, and there is at most one DRAFT, one IN_REVIEW and one ACTIVE version of a sheet;
--   * the grid of a version that is no longer a DRAFT never changes, with one exception: the narrow, counted edit by an
--     approver while the version is IN_REVIEW, which only a transaction that sets platform.review_edit = on may make
--     (the pattern of platform.record_recalculation, V043; the endpoint that sets it arrives with the approval sprint);
--   * status moves only along the lifecycle of ADR-0174 section 2, never back to DRAFT, and a finished status stays;
--   * the shape of a sheet (dimension and rows mode) is the shape of its first version; it may change only while the
--     first version is the only one and is still a DRAFT;
--   * no version is ever deleted or soft-deleted (ADR-0174 section 7). A revoked DELETE grant would not hold, because
--     the manual migration M001 grants it again on every run, so the protection is a trigger.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. The grid holds text of the organization, so it is never written to a log, an audit record or an event.

create table sheet_version (
    id                   uuid        primary key default uuidv7(),
    tenant_id            uuid        not null default platform_current_tenant() references tenant (id),
    owner_object         text        not null,
    owner_id             uuid        not null,
    version_no           integer     not null,
    status               text        not null default 'DRAFT',
    dimension            smallint    not null,
    rows_mode            text        not null,
    grid                 jsonb       not null,
    grid_hash            bytea       not null,
    grid_bytes           integer     not null,
    review_edits         integer     not null default 0,
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
    constraint sheet_version_owner_shape check (owner_object ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint sheet_version_number_range check (version_no between 1 and 100000),
    constraint sheet_version_status_known check (status in ('DRAFT', 'IN_REVIEW', 'APPROVED', 'ACTIVE', 'SUPERSEDED',
        'REJECTED', 'WITHDRAWN', 'RETIRED')),
    constraint sheet_version_shape_known check (
        (dimension = 1 and rows_mode = 'REPEATABLE')
        or (dimension = 2 and rows_mode in ('FIXED', 'FIXED_AND_REPEATABLE'))),
    constraint sheet_version_grid_is_object check (jsonb_typeof(grid) = 'object'),
    constraint sheet_version_hash_length check (octet_length(grid_hash) = 32),
    -- The application's own limit (platform.sheets.limits.max-bytes) is lower; this is the ceiling whatever it says.
    constraint sheet_version_grid_size check (grid_bytes between 2 and 5000000),
    constraint sheet_version_review_edits_range check (review_edits >= 0),
    constraint sheet_version_draft_is_untouched check (
        status <> 'DRAFT' or (submitted_at is null and decided_at is null and review_edits = 0))
);

-- Numbers are never reused (rows are never deleted, so this is a plain key).
create unique index sheet_version_owner_number
    on sheet_version (tenant_id, owner_object, owner_id, version_no) where deleted_at is null;
-- One draft, one version in review and one active version of a sheet. These are what make two instances that both ask
-- for "a new version" end with one draft and one answer, whatever the application does.
create unique index sheet_version_one_draft
    on sheet_version (tenant_id, owner_object, owner_id) where status = 'DRAFT' and deleted_at is null;
create unique index sheet_version_one_in_review
    on sheet_version (tenant_id, owner_object, owner_id) where status = 'IN_REVIEW' and deleted_at is null;
create unique index sheet_version_one_active
    on sheet_version (tenant_id, owner_object, owner_id) where status = 'ACTIVE' and deleted_at is null;

create function platform_sheet_version_guard() returns trigger
    language plpgsql
as $guard$
declare
    first_dimension smallint;
    first_rows_mode text;
    grid_changed boolean;
    shape_changed boolean;
begin
    -- Messages name the table only, never a value.
    if tg_op = 'INSERT' then
        if new.status <> 'DRAFT' then
            raise exception 'sheet guard: a version of % starts as a draft', tg_table_name
                using errcode = 'check_violation';
        end if;
        select s.dimension, s.rows_mode into first_dimension, first_rows_mode
            from sheet_version s
            where s.tenant_id = new.tenant_id and s.owner_object = new.owner_object and s.owner_id = new.owner_id
            order by s.version_no limit 1;
        if found and (first_dimension <> new.dimension or first_rows_mode <> new.rows_mode) then
            raise exception 'sheet guard: a version of % keeps the shape of the first version', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.tenant_id is distinct from old.tenant_id or new.owner_object is distinct from old.owner_object
            or new.owner_id is distinct from old.owner_id or new.version_no is distinct from old.version_no then
        raise exception 'sheet guard: the owner and the number of a version of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        raise exception 'sheet guard: the versions of % are kept', tg_table_name using errcode = 'check_violation';
    end if;

    if new.status is distinct from old.status and (old.status, new.status) not in (
            ('DRAFT', 'IN_REVIEW'),
            ('IN_REVIEW', 'APPROVED'), ('IN_REVIEW', 'REJECTED'), ('IN_REVIEW', 'WITHDRAWN'),
            ('APPROVED', 'ACTIVE'),
            ('ACTIVE', 'APPROVED'), ('ACTIVE', 'SUPERSEDED'), ('ACTIVE', 'RETIRED')) then
        raise exception 'sheet guard: this change of status of % is not part of the lifecycle', tg_table_name
            using errcode = 'check_violation';
    end if;

    grid_changed := new.grid is distinct from old.grid or new.grid_hash is distinct from old.grid_hash
        or new.grid_bytes is distinct from old.grid_bytes;
    shape_changed := new.dimension is distinct from old.dimension or new.rows_mode is distinct from old.rows_mode;

    if grid_changed or shape_changed then
        if old.status = 'DRAFT' and new.status = 'DRAFT' then
            -- The shape may be changed only while nothing else of the sheet exists (a draft v1 is alone).
            if shape_changed and old.version_no <> 1 then
                raise exception 'sheet guard: a version of % keeps the shape of the first version', tg_table_name
                    using errcode = 'check_violation';
            end if;
        elsif old.status = 'IN_REVIEW' and new.status = 'IN_REVIEW' and not shape_changed
                and coalesce(current_setting('platform.review_edit', true), '') = 'on'
                and new.review_edits = old.review_edits + 1 then
            null; -- the counted edit of an approver, see the header of this file
        else
            raise exception 'sheet guard: the grid of a version of % that is not a draft cannot change', tg_table_name
                using errcode = 'check_violation';
        end if;
    elsif new.review_edits is distinct from old.review_edits then
        raise exception 'sheet guard: only an edit of the grid counts as a review edit in %', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_sheet_version_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'sheet guard: the versions of % are kept', tg_table_name using errcode = 'check_violation';
end;
$guard$;

create trigger sheet_version_guard before insert or update on sheet_version
    for each row execute function platform_sheet_version_guard();
create trigger sheet_version_no_delete before delete on sheet_version
    for each row execute function platform_sheet_version_no_delete();
create trigger sheet_version_row_guard before insert or update on sheet_version
    for each row execute function platform_row_guard();
create trigger sheet_version_tenant_guard before update on sheet_version
    for each row execute function platform_tenant_guard();

alter table sheet_version enable row level security;
alter table sheet_version force row level security;
create policy sheet_version_insert on sheet_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy sheet_version_select on sheet_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy sheet_version_update on sheet_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy sheet_version_delete on sheet_version for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table sheet_version is
    'One version of a sheet (a grid that belongs to a record of an object). Numbered automatically, never deleted, immutable once it leaves draft (ADR-0173, ADR-0174, ADR-0186).';
comment on function platform_sheet_version_guard() is
    'Enforces the lifecycle of a sheet version: the first status, the allowed moves, an immutable grid after draft, the shape of the first version.';
