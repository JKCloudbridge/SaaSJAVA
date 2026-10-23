-- Change requests on a published template and the comments on them (Functional track FA5, ADR-0191). Expand only;
-- nothing is removed or renamed.
--
--   inspection_change_request  A person who may read a template that is in use asks for a change. The request pins the
--                              version number the person read, has a status (open, accepted, declined, done) and, when it
--                              was turned into a new version, the number of that version. It is a record of its own and
--                              grants nothing: the template and its versions are not touched by it.
--   inspection_change_comment  One comment of a request, anchored to a place of the version that was read: a row, a
--                              column or a cell by the stable identifiers of the grid (ADR-0173), or to the template as
--                              a whole (no identifier). The label of the place at that time is kept so that a comment can
--                              still be read when the place has gone from a later version.
--
-- Who sees a request is decided live by the application (the requester, and whoever may change the template), not by a
-- share, so there is no row of access to go stale. These tables hold text people typed (a title, a comment, a reason for
-- declining): it is never written to a log, an audit record or an event; those carry identifiers and counts.
--
-- What must hold even if the application had a bug:
--   * both tables are tenant-scoped with forced row level security; there is no system scope on them;
--   * a row is never deleted or soft-deleted: a request and what was said on it are kept, which is what lets a rejected
--     version keep its comments (acceptance criterion 7 of the business analysis);
--   * a comment is never changed after it was written, and is only added to a request that is open or accepted, and to
--     the template that request is about;
--   * what a request says about its template, the version read and its title never changes, and its status moves only
--     along open to accepted, declined or done; accepted to declined or done; done back to open (when the version it
--     became was not used);
--   * a request is done exactly when it names the version it became.

create table inspection_change_request (
    id                uuid        primary key default uuidv7(),
    tenant_id         uuid        not null default platform_current_tenant() references tenant (id),
    template_id       uuid        not null,
    version_no        integer     not null,
    title             text        not null,
    status            text        not null default 'OPEN',
    decision_note     text,
    decided_at        timestamptz,
    decided_by        uuid,
    resulting_version integer,
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        uuid        not null,
    updated_at        timestamptz not null default now(),
    updated_by        uuid        not null,
    deleted_at        timestamptz,
    deleted_by        uuid,
    constraint inspection_change_request_version_range check (version_no between 1 and 100000),
    constraint inspection_change_request_title_length check (char_length(title) between 1 and 200),
    constraint inspection_change_request_status_known check (status in ('OPEN', 'ACCEPTED', 'DECLINED', 'DONE')),
    constraint inspection_change_request_note_length check (decision_note is null or char_length(decision_note) <= 1000),
    constraint inspection_change_request_result_range check (
        resulting_version is null or resulting_version between 1 and 100000),
    constraint inspection_change_request_done_names_version check ((status = 'DONE') = (resulting_version is not null)),
    constraint inspection_change_request_decided_whole check (
        (status = 'OPEN') = (decided_at is null and decided_by is null))
);

create index inspection_change_request_tenant_template
    on inspection_change_request (tenant_id, template_id, created_at desc, id desc) where deleted_at is null;
-- The most open requests one person may have on a template, and the open count of a template.
create index inspection_change_request_tenant_open
    on inspection_change_request (tenant_id, template_id, created_by) where status = 'OPEN' and deleted_at is null;

create table inspection_change_comment (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    request_id    uuid        not null references inspection_change_request (id),
    template_id   uuid        not null,
    row_id        text,
    column_id     text,
    anchor_label  text,
    body          text        not null,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint inspection_change_comment_row_shape check (row_id is null or row_id ~ '^[A-Za-z0-9_-]{1,40}$'),
    constraint inspection_change_comment_column_shape check (column_id is null or column_id ~ '^[A-Za-z0-9_-]{1,40}$'),
    constraint inspection_change_comment_label_length check (anchor_label is null or char_length(anchor_label) <= 200),
    constraint inspection_change_comment_body_length check (char_length(body) between 1 and 4000)
);

create index inspection_change_comment_tenant_request
    on inspection_change_comment (tenant_id, request_id, created_at, id) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- Guards. Messages name the table only, never a value.
-- ---------------------------------------------------------------------------------------------------------------

create function platform_inspection_change_request_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'OPEN' then
            raise exception 'change request guard: a request of % starts open', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.deleted_at is not null then
        raise exception 'change request guard: the requests of % are kept', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.template_id is distinct from old.template_id or new.version_no is distinct from old.version_no
            or new.title is distinct from old.title or new.created_by is distinct from old.created_by then
        raise exception 'change request guard: what a request of % says about its template, version and title is immutable',
            tg_table_name using errcode = 'check_violation';
    end if;
    if new.status is distinct from old.status and not (
            (old.status = 'OPEN' and new.status in ('ACCEPTED', 'DECLINED', 'DONE'))
            or (old.status = 'ACCEPTED' and new.status in ('DECLINED', 'DONE'))
            or (old.status = 'DONE' and new.status = 'OPEN')) then
        raise exception 'change request guard: a request of % cannot move from % to %', tg_table_name, old.status,
            new.status using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_inspection_change_comment_guard() returns trigger
    language plpgsql
as $guard$
declare
    asked record;
begin
    if tg_op = 'UPDATE' then
        raise exception 'change comment guard: a comment of % is kept as it was written', tg_table_name
            using errcode = 'check_violation';
    end if;

    -- tg_op = 'INSERT': the request must exist, be about the same template, and still be open for comments.
    select template_id, status into asked from inspection_change_request where id = new.request_id;
    if asked.template_id is distinct from new.template_id then
        raise exception 'change comment guard: a comment of % belongs to the template of its request', tg_table_name
            using errcode = 'check_violation';
    end if;
    if asked.status not in ('OPEN', 'ACCEPTED') then
        raise exception 'change comment guard: a request that is closed takes no comment in %', tg_table_name
            using errcode = 'check_violation';
    end if;
    -- A column comes with its row only for a cell; a row alone and a column alone are places of their own.
    return new;
end;
$guard$;

create function platform_inspection_change_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'change request guard: the rows of % are kept', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_change_request_guard before insert or update on inspection_change_request
    for each row execute function platform_inspection_change_request_guard();
create trigger inspection_change_request_no_delete before delete on inspection_change_request
    for each row execute function platform_inspection_change_no_delete();
create trigger inspection_change_request_row_guard before insert or update on inspection_change_request
    for each row execute function platform_row_guard();
create trigger inspection_change_request_tenant_guard before update on inspection_change_request
    for each row execute function platform_tenant_guard();

create trigger inspection_change_comment_guard before insert or update on inspection_change_comment
    for each row execute function platform_inspection_change_comment_guard();
create trigger inspection_change_comment_no_delete before delete on inspection_change_comment
    for each row execute function platform_inspection_change_no_delete();
create trigger inspection_change_comment_row_guard before insert or update on inspection_change_comment
    for each row execute function platform_row_guard();
create trigger inspection_change_comment_tenant_guard before update on inspection_change_comment
    for each row execute function platform_tenant_guard();

-- ---------------------------------------------------------------------------------------------------------------
-- Row level security (ADR-0003): enabled and forced, one policy for each statement.
-- ---------------------------------------------------------------------------------------------------------------

alter table inspection_change_request enable row level security;
alter table inspection_change_request force row level security;
create policy inspection_change_request_insert on inspection_change_request for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_change_request_select on inspection_change_request for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_change_request_update on inspection_change_request for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_change_request_delete on inspection_change_request for delete
    using (tenant_id = (select platform_current_tenant()));

alter table inspection_change_comment enable row level security;
alter table inspection_change_comment force row level security;
create policy inspection_change_comment_insert on inspection_change_comment for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_change_comment_select on inspection_change_comment for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_change_comment_update on inspection_change_comment for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_change_comment_delete on inspection_change_comment for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_change_request is
    'A request for a change to a published template, pinned to the version read. Never deleted (ADR-0191).';
comment on table inspection_change_comment is
    'A comment of a change request, anchored to a row, a column or a cell of the version read. Never changed or deleted (ADR-0191).';
