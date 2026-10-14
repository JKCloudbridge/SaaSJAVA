-- Record access: the organization-wide default of each object, the sharing rules, and what the role hierarchy needs
-- to name owners (Sprint 17, ADR-0115, ADR-0116).
--
--   object_sharing_setting   One row per object that an administrator has set up: the organization-wide default
--                            (PRIVATE, PUBLIC_READ or PUBLIC_READ_WRITE) and whether a manager reads what the people
--                            below them own ("grant access using the hierarchy"). An object with no row has the default
--                            PUBLIC_READ_WRITE with the hierarchy on, which is how the platform behaved before this
--                            migration: nothing changes for anybody until an administrator sets an object up.
--   sharing_rule             A rule that gives records to a role, a role with its subordinates or a public group:
--                            OWNER (the records owned by the members of a role), GROUP (owned by the members of a public
--                            group) or CRITERIA (the records that meet a closed condition on their fields). The
--                            condition is data (the JSON of the query filter model), never text that is run. The access
--                            it gives is read or read and write; no rule gives the right to delete.
--   member_access.user_id    The user behind the membership, copied by a trigger and immutable. Records are owned by
--                            users and the hierarchy is made of memberships; with the user on the same row the security
--                            module can turn "the members below me" into owners without reading the membership table.
--
-- Both new tables are tenant-scoped with forced row level security. They carry the security version trigger: what a
-- member may see depends on them, and the cache of ADR-0053 must not outlive a change. So does the role tree
-- (security_role), which until now decided nothing but is the hierarchy since this sprint.
--
-- What must hold even if the application had a bug:
--   * a rule's source and target are live roles or groups of the same organization (checked on every write, because a
--     foreign key check bypasses row level security);
--   * a role or a group that a live rule names cannot be removed (the services say so in words; the database is the
--     backstop);
--   * a rule has the parts its kind needs and no others.

-- ---------------------------------------------------------------------------------------------------------------
-- member_access.user_id
-- ---------------------------------------------------------------------------------------------------------------

alter table member_access add column user_id uuid;

-- The existing rows are filled without the application's guards: the update changes nothing the guards watch, and
-- they read tables whose forced row level security binds the owner too (a migration has no tenant). As in V013 and V018
-- forcing is switched off for this statement and back on in the same transaction.
alter table membership no force row level security;
alter table member_access no force row level security;
alter table member_access disable trigger user;
update member_access ma set user_id = m.user_id from membership m where m.id = ma.membership_id;
alter table member_access enable trigger user;
alter table member_access force row level security;
alter table membership force row level security;

alter table member_access alter column user_id set not null;

create index member_access_tenant_user on member_access (tenant_id, user_id) where deleted_at is null;

create function platform_member_access_user() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        select m.user_id into new.user_id from membership m
         where m.id = new.membership_id and m.tenant_id = new.tenant_id;
        if new.user_id is null then
            raise exception 'access guard: a member holding access belongs to this organization'
                using errcode = 'check_violation';
        end if;
    elsif new.user_id is distinct from old.user_id then
        raise exception 'access guard: the user of a member does not change' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_member_access_user() is
    'Copies the user of the membership onto the member''s access row and keeps it immutable (ADR-0115).';

create trigger member_access_user_guard before insert or update on member_access
    for each row execute function platform_member_access_user();

-- ---------------------------------------------------------------------------------------------------------------
-- object_sharing_setting
-- ---------------------------------------------------------------------------------------------------------------

create table object_sharing_setting (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    object_key          text        not null,
    default_access      text        not null default 'PUBLIC_READ_WRITE',
    grant_via_hierarchy boolean     not null default true,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint object_sharing_setting_key_shape check (object_key ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint object_sharing_setting_default_known
        check (default_access in ('PRIVATE', 'PUBLIC_READ', 'PUBLIC_READ_WRITE'))
);

create unique index object_sharing_setting_tenant_object on object_sharing_setting (tenant_id, object_key)
    where deleted_at is null;

create trigger object_sharing_setting_row_guard before insert or update on object_sharing_setting
    for each row execute function platform_row_guard();
create trigger object_sharing_setting_tenant_guard before update on object_sharing_setting
    for each row execute function platform_tenant_guard();

alter table object_sharing_setting enable row level security;
alter table object_sharing_setting force row level security;
create policy object_sharing_setting_insert on object_sharing_setting for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy object_sharing_setting_select on object_sharing_setting for select
    using (tenant_id = (select platform_current_tenant()));
create policy object_sharing_setting_update on object_sharing_setting for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy object_sharing_setting_delete on object_sharing_setting for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table object_sharing_setting is
    'The organization-wide default of an object and whether managers read what their subordinates own. No row means PUBLIC_READ_WRITE with the hierarchy on. Tenant-scoped, row level security forced (ADR-0116).';

-- ---------------------------------------------------------------------------------------------------------------
-- sharing_rule
-- ---------------------------------------------------------------------------------------------------------------

create table sharing_rule (
    id            uuid        primary key default uuidv7(),
    tenant_id     uuid        not null default platform_current_tenant() references tenant (id),
    object_key    text        not null,
    name          text        not null,
    kind          text        not null,
    source_type   text,
    source_id     uuid,
    criterion     jsonb,
    target_type   text        not null,
    target_id     uuid        not null,
    access_level  text        not null,
    active        boolean     not null default true,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    created_by    uuid        not null,
    updated_at    timestamptz not null default now(),
    updated_by    uuid        not null,
    deleted_at    timestamptz,
    deleted_by    uuid,
    constraint sharing_rule_key_shape check (object_key ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint sharing_rule_name_length check (char_length(name) between 1 and 80),
    constraint sharing_rule_kind_known check (kind in ('OWNER', 'GROUP', 'CRITERIA')),
    constraint sharing_rule_source_known check (source_type is null
        or source_type in ('ROLE', 'ROLE_AND_SUBORDINATES', 'GROUP')),
    constraint sharing_rule_target_known check (target_type in ('ROLE', 'ROLE_AND_SUBORDINATES', 'GROUP')),
    constraint sharing_rule_level_known check (access_level in ('READ', 'READ_WRITE')),
    constraint sharing_rule_parts_by_kind check (
        (kind = 'OWNER' and source_type in ('ROLE', 'ROLE_AND_SUBORDINATES') and source_id is not null
            and criterion is null)
        or (kind = 'GROUP' and source_type = 'GROUP' and source_id is not null and criterion is null)
        or (kind = 'CRITERIA' and source_type is null and source_id is null and criterion is not null
            and jsonb_typeof(criterion) = 'object')),
    constraint sharing_rule_criterion_size check (criterion is null or pg_column_size(criterion) <= 16384)
);

create unique index sharing_rule_tenant_name on sharing_rule (tenant_id, object_key, lower(name))
    where deleted_at is null;
create index sharing_rule_tenant_object on sharing_rule (tenant_id, object_key) where deleted_at is null;
create index sharing_rule_tenant_target on sharing_rule (tenant_id, target_id) where deleted_at is null;
create index sharing_rule_tenant_source on sharing_rule (tenant_id, source_id)
    where source_id is not null and deleted_at is null;

create function platform_sharing_rule_guard() returns trigger
    language plpgsql
as $guard$
declare
    found boolean;
begin
    if tg_op = 'UPDATE' and (new.object_key is distinct from old.object_key or new.kind is distinct from old.kind) then
        raise exception 'sharing guard: the object and the kind of a rule do not change' using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        return new;
    end if;
    if new.source_type is not null then
        if new.source_type = 'GROUP' then
            found := exists (select 1 from public_group g where g.id = new.source_id
                                and g.tenant_id = new.tenant_id and g.deleted_at is null);
        else
            found := exists (select 1 from security_role r where r.id = new.source_id
                                and r.tenant_id = new.tenant_id and r.deleted_at is null);
        end if;
        if not found then
            raise exception 'sharing guard: the source of a rule must be a role or group of this organization'
                using errcode = 'check_violation';
        end if;
    end if;
    if new.target_type = 'GROUP' then
        found := exists (select 1 from public_group g where g.id = new.target_id
                            and g.tenant_id = new.tenant_id and g.deleted_at is null);
    else
        found := exists (select 1 from security_role r where r.id = new.target_id
                            and r.tenant_id = new.tenant_id and r.deleted_at is null);
    end if;
    if not found then
        raise exception 'sharing guard: the target of a rule must be a role or group of this organization'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_sharing_rule_guard() is
    'A sharing rule names live roles or groups of its own organization, and its object and kind never change (ADR-0116).';

create trigger sharing_rule_row_guard before insert or update on sharing_rule
    for each row execute function platform_row_guard();
create trigger sharing_rule_tenant_guard before update on sharing_rule
    for each row execute function platform_tenant_guard();
create trigger sharing_rule_use_guard before insert or update on sharing_rule
    for each row execute function platform_sharing_rule_guard();

alter table sharing_rule enable row level security;
alter table sharing_rule force row level security;
create policy sharing_rule_insert on sharing_rule for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy sharing_rule_select on sharing_rule for select
    using (tenant_id = (select platform_current_tenant()));
create policy sharing_rule_update on sharing_rule for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy sharing_rule_delete on sharing_rule for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table sharing_rule is
    'Gives the records of an object to a role or group: by owner, by owning group, or by a closed condition on the fields. Tenant-scoped, row level security forced (ADR-0116).';

-- A role or a group that a live rule names is in use: it is not removed. The services answer in words; this is the
-- backstop for any other path.
create function platform_sharing_rule_in_use() returns trigger
    language plpgsql
as $guard$
begin
    if new.deleted_at is not null and old.deleted_at is null and exists (
            select 1 from sharing_rule s
             where s.tenant_id = old.tenant_id and s.deleted_at is null
               and (s.source_id = old.id or s.target_id = old.id)) then
        raise exception 'sharing guard: a role or group that a sharing rule uses cannot be removed'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_sharing_rule_in_use() is
    'Refuses to remove a role or a public group that a live sharing rule names (ADR-0116).';

create trigger security_role_sharing_in_use before update on security_role
    for each row execute function platform_sharing_rule_in_use();
create trigger public_group_sharing_in_use before update on public_group
    for each row execute function platform_sharing_rule_in_use();

-- ---------------------------------------------------------------------------------------------------------------
-- the security version (ADR-0053)
-- ---------------------------------------------------------------------------------------------------------------

create trigger object_sharing_setting_security_version after insert or update or delete on object_sharing_setting
    for each row execute function platform_security_version_bump();
create trigger sharing_rule_security_version after insert or update or delete on sharing_rule
    for each row execute function platform_security_version_bump();
create trigger security_role_security_version after insert or update or delete on security_role
    for each row execute function platform_security_version_bump();
