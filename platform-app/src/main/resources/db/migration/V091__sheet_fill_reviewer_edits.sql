-- The reviewer's edit of a fill-in (Functional track, sheet engine SH5, ADR-0258). Expand only; nothing is removed.
--
-- A cell of a sheet in format version 2 may be of the class "reviewer": the person asked to review the fill-in fills it
-- while the review is pending. The cells of a sent fill-in were fixed except for the counted correction of V069, which
-- is the act of a person who may override the lock of an approval. The reviewer's edit is a different act by a different
-- kind of person, so it gets a window of its own, counted apart (review_edits), raised by exactly one other setting
-- (platform.fill_review_edit). The two windows cannot be open at once, so one write is never two acts.
--
-- What must hold even if the application had a bug (proved by direct statements in SheetFillGuardIT):
--   * the cells change in a submitted fill-in only inside exactly one of the two windows, and only while the status is
--     SUBMITTED for the reviewer's edit (never after the decision: an approved or returned fill-in is not edited by a
--     reviewer);
--   * review_edits moves up by exactly one with a change of the cells and never otherwise; it never moves in the
--     status changes, the archive move or an archived row; a fill-in starts at zero;
--   * everything the guard of V071 enforced still holds. The function is replaced as a whole.

alter table sheet_fill
    add column review_edits integer not null default 0 check (review_edits >= 0);

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
                or new.archived_at is not null or new.review_edits <> 0 then
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
                or new.review_edits is distinct from old.review_edits
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
                or new.corrections is distinct from old.corrections
                or new.review_edits is distinct from old.review_edits then
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
        if cells_changed or new.corrections is distinct from old.corrections
                or new.review_edits is distinct from old.review_edits then
            raise exception 'fill guard: the status and the cells of % do not change together', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if cells_changed then
        if old.status in ('DRAFT', 'RETURNED') and new.corrections is not distinct from old.corrections
                and new.review_edits is not distinct from old.review_edits then
            null; -- the filler's own work
        elsif old.status in ('SUBMITTED', 'APPROVED')
                and coalesce(current_setting('platform.fill_correction', true), '') = 'on'
                and coalesce(current_setting('platform.fill_review_edit', true), '') <> 'on'
                and new.corrections = old.corrections + 1
                and new.review_edits is not distinct from old.review_edits then
            null; -- the counted correction after submit, see V069
        elsif old.status = 'SUBMITTED'
                and coalesce(current_setting('platform.fill_review_edit', true), '') = 'on'
                and coalesce(current_setting('platform.fill_correction', true), '') <> 'on'
                and new.review_edits = old.review_edits + 1
                and new.corrections is not distinct from old.corrections then
            null; -- the counted edit of the reviewer's cells while the review is pending, see V091
        else
            raise exception 'fill guard: the cells of a fill-in of % that was submitted cannot change', tg_table_name
                using errcode = 'check_violation';
        end if;
    elsif new.corrections is distinct from old.corrections or new.review_edits is distinct from old.review_edits then
        raise exception 'fill guard: only a change of the cells counts as a correction in %', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_sheet_fill_guard() is
    'Enforces the rules of a fill-in: started on the version in use, an immutable pin, the allowed moves of status, cells that change only in draft, returned, by the counted correction or by the counted reviewer edit while the review is pending, and the one-way move of the cells to the archive (ADR-0192, ADR-0193, ADR-0258).';
comment on column sheet_fill.review_edits is
    'How many times a reviewer changed the cells while the review was pending; raised by exactly one in the window platform.fill_review_edit (ADR-0258).';
