-- The memory of publishing a template to the people of a department (Functional track FA4, ADR-0188). Expand only;
-- nothing is removed or renamed.
--
--   inspection_publication  One row for each group a template was published to. Publishing is a read share of the
--                           template's header record to a group; the shares are the access, this table is the memory:
--                           it says which shares the publish made itself (made_share), so that taking the template back
--                           removes exactly those and never a share somebody gave by hand with the share panel, and it
--                           says which location and department the group stood for, who published and when.
--
-- A row ends by being revoked (unpublished, or the audience changed); it is never deleted, so the history of who a
-- template was published to stays. A group that is removed later takes its shares with it (the existing removal of
-- shares of a removed group); its row stays and says what it was.
--
-- What must hold even if the application had a bug:
--   * the table is tenant-scoped with forced row level security; there is no system scope on it;
--   * a template is published to a group at most once at a time (partial unique index, which also leads with the tenant);
--   * a row is never deleted or soft-deleted, and once revoked it never changes again;
--   * what a row says about its template, group, location, department and share never changes;
--   * a revoked row has the time, the person and the reason, and a live row has none of them.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. Location and department are codes of the organization, so a row's codes are never written to a log, an audit
-- record or an event; the audit names the template by its identifier and counts.

create table inspection_publication (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    template_id   uuid        not null,
    version_no    integer     not null,
    location      text        not null,
    department    text        not null,
    group_id      uuid        not null,
    made_share    boolean     not null,
    revoked_at    timestamptz,
    revoked_by    uuid,
    ended_reason  text,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint inspection_publication_version_range check (version_no between 1 and 100000),
    constraint inspection_publication_codes_shape check (
        location ~ '^[A-Za-z0-9_-]{1,10}$' and department ~ '^[A-Za-z0-9_-]{1,10}$'),
    constraint inspection_publication_end_known check (ended_reason is null or ended_reason in ('unpublished', 'changed')),
    constraint inspection_publication_end_whole check (
        (revoked_at is null and revoked_by is null and ended_reason is null)
        or (revoked_at is not null and revoked_by is not null and ended_reason is not null))
);

-- A template is published to a group at most once at a time; a revoked row does not count.
create unique index inspection_publication_live_group
    on inspection_publication (tenant_id, template_id, group_id) where revoked_at is null and deleted_at is null;
create index inspection_publication_tenant_template
    on inspection_publication (tenant_id, template_id, created_at);

create function platform_inspection_publication_guard() returns trigger
    language plpgsql
as $guard$
begin
    -- Messages name the table only, never a value.
    if tg_op = 'INSERT' then
        if new.revoked_at is not null then
            raise exception 'publication guard: a publication of % starts live', tg_table_name
                using errcode = 'check_violation';
        end if;
        return new;
    end if;

    -- tg_op = 'UPDATE'
    if new.deleted_at is not null then
        raise exception 'publication guard: the publications of % are kept', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.revoked_at is not null then
        raise exception 'publication guard: a revoked publication of % does not change', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.template_id is distinct from old.template_id or new.group_id is distinct from old.group_id
            or new.location is distinct from old.location or new.department is distinct from old.department
            or new.made_share is distinct from old.made_share then
        raise exception 'publication guard: what a publication of % says about its template and group is immutable',
            tg_table_name using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create function platform_inspection_publication_no_delete() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'publication guard: the publications of % are kept', tg_table_name
        using errcode = 'check_violation';
end;
$guard$;

create trigger inspection_publication_guard before insert or update on inspection_publication
    for each row execute function platform_inspection_publication_guard();
create trigger inspection_publication_no_delete before delete on inspection_publication
    for each row execute function platform_inspection_publication_no_delete();
create trigger inspection_publication_row_guard before insert or update on inspection_publication
    for each row execute function platform_row_guard();
create trigger inspection_publication_tenant_guard before update on inspection_publication
    for each row execute function platform_tenant_guard();

alter table inspection_publication enable row level security;
alter table inspection_publication force row level security;
create policy inspection_publication_insert on inspection_publication for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_publication_select on inspection_publication for select
    using (tenant_id = (select platform_current_tenant()));
create policy inspection_publication_update on inspection_publication for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy inspection_publication_delete on inspection_publication for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table inspection_publication is
    'Which groups a template was published to, and which of the read shares the publish made itself. Never deleted (ADR-0188).';
