-- The archive of old fill-ins (Functional track FF2, ADR-0193). Expand only; nothing is removed or renamed.
--
-- A fill-in is never deleted (decision 10 of the business analysis), but about a thousand a day of one template make
-- the cells (the part that is heavy: about 5.7 KB each in TOAST, ADR-0173 section 6) the bulk of the database. An
-- approved fill-in older than the archive age has its cells document moved by a job, as one compressed document, to the
-- object store. The row stays: its status, pin, hash, size and row count, and a pointer to the document. The cells
-- column keeps an empty object so every statement and every constraint on the table still holds. The document is read
-- back on demand and verified against the hash the row still holds. The values copied for reports (V070) stay too.
--
-- What must hold even if the application had a bug (proved by direct statements in FillArchiveGuardIT):
--   * only an approved fill-in is archived, only in a transaction that sets platform.fill_archive = on, and only by
--     the move itself: the cells become the empty object, the hash, size and row count (which describe the archived
--     document) stay, the status and corrections do not move, and the pointer is set;
--   * an archived fill-in is read-only for ever: its cells, its pointer, its status and its corrections cannot change,
--     not even by the counted correction of V069, and it is not archived again;
--   * a fill-in is started unarchived, and the pointer is all or nothing.
-- The guard of V069 is replaced as a whole (a function is replaced, never patched); everything it enforced still holds.

alter table sheet_fill
    add column archived_at   timestamptz,
    add column archive_key   text,
    add column archive_bytes integer;

alter table sheet_fill
    add constraint sheet_fill_archive_pointer check (
        (archived_at is null and archive_key is null and archive_bytes is null)
        or (archived_at is not null and archive_key is not null and archive_bytes is not null
            and archive_bytes > 0));

-- The job looks for approved fill-ins that have been decided longer ago than the archive age, oldest first.
create index sheet_fill_archivable on sheet_fill (tenant_id, decided_at, id)
    where status = 'APPROVED' and archived_at is null and deleted_at is null;

create or replace function platform_sheet_fill_guard() returns trigger
    language plpgsql
as $guard$
declare
    cells_changed boolean;
begin
    -- Messages name the table only, never a value.
    if tg_op = 'INSERT' then
        if new.status <> 'DRAFT' or new.corrections <> 0 or new.submitted_at is not null
                or new.decided_at is not null or new.approval_request_id is not null
                or new.archived_at is not null then
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

    -- An archived fill-in is read-only for ever.
    if old.archived_at is not null then
        if cells_changed or new.status is distinct from old.status
                or new.corrections is distinct from old.corrections
                or new.archived_at is distinct from old.archived_at
                or new.archive_key is distinct from old.archive_key
                or new.archive_bytes is distinct from old.archive_bytes then
            raise exception 'fill guard: an archived fill-in of % cannot change', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- The move to the archive: nothing about the fill-in changes but where its cells are.
    if new.archived_at is not null then
        if old.status <> 'APPROVED' or new.status is distinct from old.status
                or coalesce(current_setting('platform.fill_archive', true), '') <> 'on'
                or new.cells <> '{}'::jsonb
                or new.cells_hash is distinct from old.cells_hash
                or new.cells_bytes is distinct from old.cells_bytes
                or new.row_count is distinct from old.row_count
                or new.corrections is distinct from old.corrections then
            raise exception 'fill guard: this archive move of a fill-in of % is not part of the lifecycle',
                tg_table_name using errcode = 'check_violation';
        end if;
        return new;
    end if;

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
            null; -- the counted correction after submit, see V069
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

comment on function platform_sheet_fill_guard() is
    'Enforces the rules of a fill-in: started on the version in use, an immutable pin, the allowed moves of status, cells that change only in draft, returned or by the counted correction, and the one-way move of the cells to the archive (ADR-0192, ADR-0193).';
comment on column sheet_fill.archive_key is
    'Where the compressed cells document of an archived fill-in is kept in the object store; null while the cells are in this row (ADR-0193).';
