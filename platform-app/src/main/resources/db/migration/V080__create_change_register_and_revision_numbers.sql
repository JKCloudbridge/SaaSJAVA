-- The register form of a change request and the gapless revision number (Functional track FA8, ADR-0202). Expand only;
-- nothing is removed or renamed.
--
--   inspection_change_register  One row for each change request: the Document Change Register form. The person who
--                               raises the request writes the top of it once; the people who approve it may complete the
--                               bottom while the request is open; the platform stamps the new revision at the moment
--                               the request is published. The row is the single set of values: the form view and the
--                               Excel view of a request are both drawn from it.
--   sheet_version.revision_no   The revision number people read (0, 1, 2 ...). Set once, by this guard, when a version
--                               first becomes the one in use, as one more than the highest revision number before it,
--                               so a version that was rejected or withdrawn never uses a number. A version that became
--                               active before this release has none stored; the application reads it as the stored
--                               version number minus one, as before.
--
-- What must hold even if the application had a bug (proved by direct statements in the guard tests):
--   * the register is tenant-scoped with forced row level security; there is no system scope on it;
--   * a row is made and changed only by the cluster's own acts: every write needs the transaction setting
--     platform.change_register_write = 'on', which only the store raises, in the statement itself;
--   * what the person wrote when they raised the request (the request number, the change requested, the basis and the
--     horizontal deployment) and the facts the platform wrote then (document, revision, who and when) never change;
--   * the rest is completed only while its request is OPEN, and the new revision number and its date are written only
--     by the publish (platform.change_register_result = 'on'), once;
--   * a row is never deleted or soft-deleted, and a request has one row;
--   * a revision number never changes once it is set, and only the move of a version to the one in use sets it.
-- The columns hold text of the organization (the words of the form), so a row is never written to a log, an audit
-- record, an event or a notice; the audit says which field names changed and nothing else.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. The request is an identifier only: the request table is not referenced.

alter table sheet_version add column revision_no integer;
alter table sheet_version add constraint sheet_version_revision_range check (revision_no is null or revision_no between 0 and 99999);

create or replace function platform_sheet_version_guard() returns trigger
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
        if new.revision_no is not null then
            raise exception 'sheet guard: the revision number of % is set when a version first becomes the one in use',
                tg_table_name using errcode = 'check_violation';
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

    -- The revision number: set once, by the move to the one in use, as one more than the highest set before it (or, for
    -- a sheet whose earlier versions were in use before numbers were stored, the count of those versions).
    if old.revision_no is not null and new.revision_no is distinct from old.revision_no then
        raise exception 'sheet guard: the revision number of a version of % never changes', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.status = 'ACTIVE' and old.status <> 'ACTIVE' and new.revision_no is null then
        new.revision_no := coalesce(
            (select max(s.revision_no) + 1 from sheet_version s
                where s.tenant_id = new.tenant_id and s.owner_object = new.owner_object
                    and s.owner_id = new.owner_id and s.id <> new.id and s.revision_no is not null
                    and s.deleted_at is null),
            (select count(*) from sheet_version s
                where s.tenant_id = new.tenant_id and s.owner_object = new.owner_object
                    and s.owner_id = new.owner_id and s.version_no < new.version_no
                    and s.status in ('ACTIVE', 'SUPERSEDED', 'RETIRED') and s.deleted_at is null));
    elsif old.revision_no is null and new.revision_no is not null then
        raise exception 'sheet guard: the revision number of a version of % is set only when it becomes the one in use',
            tg_table_name using errcode = 'check_violation';
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
            null; -- the counted edit of an approver, see the header of V064
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

create table inspection_change_register (
    id                      uuid        primary key default uuidv7(),
    tenant_id               uuid        not null default platform_current_tenant() references tenant (id),
    request_id              uuid        not null,
    template_id             uuid        not null,
    -- Written once, when the request is raised: the words of the person ...
    request_no              text        not null,
    change_requested        text        not null,
    basis_for_change        text,
    horizontal_deployment   text,
    -- ... and the facts the platform wrote then.
    document_name           text        not null,
    document_no             text,
    rev_no                  text        not null,
    requested_by_name       text,
    requested_at            timestamptz not null default now(),
    -- Completed by the people who approve, while the request is open (the platform fills what it knows).
    department              text,
    obsoleted_format        text,
    obsoleted_effective_on  date,
    approved_by             text,
    signature_note          text,
    effective_on            date,
    server_updated          text,
    master_list_updated     text,
    -- Written by the publish alone, once.
    new_rev_no              text,
    new_revision_at         timestamptz,
    version                 bigint      not null default 0,
    created_at              timestamptz not null default now(),
    created_by              uuid        not null,
    updated_at              timestamptz not null default now(),
    updated_by              uuid        not null,
    deleted_at              timestamptz,
    deleted_by              uuid,
    constraint inspection_change_register_request_no check (char_length(request_no) between 1 and 30),
    constraint inspection_change_register_change check (char_length(change_requested) between 1 and 4000),
    constraint inspection_change_register_basis check (basis_for_change is null or char_length(basis_for_change) <= 4000),
    constraint inspection_change_register_horizontal check (
        horizontal_deployment is null or char_length(horizontal_deployment) <= 2000),
    constraint inspection_change_register_document check (
        char_length(document_name) <= 400 and (document_no is null or char_length(document_no) <= 100)
        and char_length(rev_no) <= 10),
    constraint inspection_change_register_person check (
        requested_by_name is null or char_length(requested_by_name) <= 200),
    constraint inspection_change_register_department check (department is null or char_length(department) <= 1000),
    constraint inspection_change_register_obsoleted check (
        obsoleted_format is null or char_length(obsoleted_format) <= 1000),
    constraint inspection_change_register_approved check (approved_by is null or char_length(approved_by) <= 2000),
    constraint inspection_change_register_signature check (
        signature_note is null or char_length(signature_note) <= 500),
    constraint inspection_change_register_answers check (
        (server_updated is null or server_updated in ('YES', 'NO'))
        and (master_list_updated is null or master_list_updated in ('YES', 'NO'))),
    constraint inspection_change_register_new_rev check (new_rev_no is null or char_length(new_rev_no) <= 10)
);

create unique index inspection_change_register_request
    on inspection_change_register (tenant_id, request_id) where deleted_at is null;
create index inspection_change_register_tenant_template
    on inspection_change_register (tenant_id, template_id);

create function platform_inspection_change_register_guard() returns trigger
    language plpgsql
as $guard$
declare
    open_request boolean;
begin
    -- Messages name the table only, never a value.
    if coalesce(current_setting('platform.change_register_write', true), '') <> 'on' then
        raise exception 'register guard: the register of % is written only by the acts of the template cluster',
            tg_table_name using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.new_rev_no is not null or new.new_revision_at is not null then
            raise exception 'register guard: the new revision of % is written by the publish alone', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.tenant_id is distinct from old.tenant_id or new.request_id is distinct from old.request_id
            or new.template_id is distinct from old.template_id
            or new.request_no is distinct from old.request_no
            or new.change_requested is distinct from old.change_requested
            or new.basis_for_change is distinct from old.basis_for_change
            or new.horizontal_deployment is distinct from old.horizontal_deployment
            or new.document_name is distinct from old.document_name
            or new.document_no is distinct from old.document_no or new.rev_no is distinct from old.rev_no
            or new.requested_by_name is distinct from old.requested_by_name
            or new.requested_at is distinct from old.requested_at then
        raise exception 'register guard: what was written when the request of % was raised never changes',
            tg_table_name using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        raise exception 'register guard: the registers of % are kept', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.department is distinct from old.department or new.obsoleted_format is distinct from old.obsoleted_format
            or new.obsoleted_effective_on is distinct from old.obsoleted_effective_on
            or new.approved_by is distinct from old.approved_by or new.signature_note is distinct from old.signature_note
            or new.effective_on is distinct from old.effective_on
            or new.server_updated is distinct from old.server_updated
            or new.master_list_updated is distinct from old.master_list_updated
            or new.new_rev_no is distinct from old.new_rev_no
            or new.new_revision_at is distinct from old.new_revision_at then
        select exists (select 1 from inspection_change_request r
                where r.tenant_id = new.tenant_id and r.id = new.request_id and r.status = 'OPEN'
                    and r.deleted_at is null) into open_request;
        if not open_request then
            raise exception 'register guard: the register of % is final once its request is answered', tg_table_name
                using errcode = 'check_violation';
        end if;
    end if;
    if new.new_rev_no is distinct from old.new_rev_no or new.new_revision_at is distinct from old.new_revision_at then
        if coalesce(current_setting('platform.change_register_result', true), '') <> 'on'
                or old.new_rev_no is not null or old.new_revision_at is not null then
            raise exception 'register guard: the new revision of % is written once, by the publish alone',
                tg_table_name using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create function platform_inspection_change_register_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'register guard: the registers of % are kept', tg_table_name using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_change_register_guard before insert or update on inspection_change_register
    for each row execute function platform_inspection_change_register_guard();
create trigger inspection_change_register_no_delete before delete on inspection_change_register
    for each row execute function platform_inspection_change_register_no_delete();
create trigger inspection_change_register_row_guard before insert or update on inspection_change_register
    for each row execute function platform_row_guard();
create trigger inspection_change_register_tenant_guard before update on inspection_change_register
    for each row execute function platform_tenant_guard();

alter table inspection_change_register enable row level security;
alter table inspection_change_register force row level security;
create policy inspection_change_register_insert on inspection_change_register for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_change_register_select on inspection_change_register for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_change_register_update on inspection_change_register for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_change_register_delete on inspection_change_register for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_change_register is
    'The Document Change Register form of a change request: written when raised, completed by approvers while open, stamped by the publish (ADR-0202).';
