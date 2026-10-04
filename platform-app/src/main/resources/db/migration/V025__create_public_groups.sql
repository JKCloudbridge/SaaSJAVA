-- Public groups (Sprint 8, ADR-0047, ADR-0048).
--
-- A public group is a named set of people and of other groups (nesting), owned by one organization. A group can be given
-- access policies: everyone in the group, directly or through nested groups, then holds what those policies give (a union,
-- like everything else, ADR-0040). Groups are also what sharing rules, approvals and notifications read later.
--
-- Three tables, all tenant-scoped (ADR-0015, ADR-0047): public_group, public_group_member (a person or a group is a member of
-- a group) and public_group_access_policy (an access policy given to a group). Every table has tenant_id, row level security
-- enabled and forced, a policy on platform_current_tenant(), the tenant guard trigger and a tenant-first index. A row
-- never points at a row of another organization (the guards check it, because a foreign key check bypasses row level
-- security).
--
-- What must hold even if the application had a bug:
--   * groups form no loop: a group is never, directly or through other groups, a member of itself. The check walks down
--     from the group that is being added, under one lock per organization, so two administrators adding two groups to each
--     other at the same moment cannot together make a loop (the same method as the role tree of V020, but a graph);
--   * a person in a group is an active member of the organization, a group in a group is a live group of the organization;
--   * a licence-bound access policy cannot be given to a group (a licence belongs to a person, not to a group);
--   * a policy that a group uses is "in use": it is not removed and keeps its licence requirement;
--   * the last member who can manage access stays (ADR-0044) also when the ability reaches people through a group: the
--     holder count below follows groups, and the three tables are watched like the others.

-- ---------------------------------------------------------------------------------------------------------------
-- tables
-- ---------------------------------------------------------------------------------------------------------------

create table public_group (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    name        text        not null,
    description text        not null default '',
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint public_group_name_length check (char_length(name) between 1 and 80),
    constraint public_group_description_length check (char_length(description) <= 500)
);

create unique index public_group_tenant_name on public_group (tenant_id, lower(name)) where deleted_at is null;

-- A member of a group: exactly one of a person (membership) or a group.
create table public_group_member (
    id                   uuid        primary key default uuidv7(),
    tenant_id            uuid        not null default platform_current_tenant() references tenant (id),
    group_id             uuid        not null references public_group (id),
    member_membership_id uuid        references membership (id),
    member_group_id      uuid        references public_group (id),
    version              bigint      not null default 0,
    created_at           timestamptz not null default now(),
    created_by           uuid        not null,
    updated_at           timestamptz not null default now(),
    updated_by           uuid        not null,
    deleted_at           timestamptz,
    deleted_by           uuid,
    constraint public_group_member_one_kind check (num_nonnulls(member_membership_id, member_group_id) = 1),
    constraint public_group_member_not_itself check (member_group_id is null or member_group_id <> group_id)
);

create unique index public_group_member_tenant_person
    on public_group_member (tenant_id, group_id, member_membership_id)
    where member_membership_id is not null and deleted_at is null;
create unique index public_group_member_tenant_group
    on public_group_member (tenant_id, group_id, member_group_id)
    where member_group_id is not null and deleted_at is null;
create index public_group_member_tenant_person_of
    on public_group_member (tenant_id, member_membership_id) where member_membership_id is not null and deleted_at is null;
create index public_group_member_tenant_group_of
    on public_group_member (tenant_id, member_group_id) where member_group_id is not null and deleted_at is null;

create table public_group_access_policy (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    group_id         uuid        not null references public_group (id),
    access_policy_id uuid        not null references access_policy (id),
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid
);

create unique index public_group_access_policy_tenant_pair
    on public_group_access_policy (tenant_id, group_id, access_policy_id) where deleted_at is null;
create index public_group_access_policy_tenant_policy
    on public_group_access_policy (tenant_id, access_policy_id) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards
-- ---------------------------------------------------------------------------------------------------------------

-- What a group holds as a member: a live group of this organization, an active member or a live group of this
-- organization, and no loop. The lock is one per organization and is taken after the access lock (the trigger of the
-- access guard sorts first), so the order of locks is the same everywhere.
create function platform_group_member_guard() returns trigger
    language plpgsql
as $guard$
begin
    perform pg_advisory_xact_lock(hashtextextended('public-groups:' || new.tenant_id::text, 0));
    if tg_op = 'UPDATE' and (new.group_id is distinct from old.group_id
            or new.member_membership_id is distinct from old.member_membership_id
            or new.member_group_id is distinct from old.member_group_id) then
        raise exception 'group guard: a group membership does not change its group or its member'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        return new;
    end if;
    if not exists (select 1 from public_group g
                    where g.id = new.group_id and g.tenant_id = new.tenant_id and g.deleted_at is null) then
        raise exception 'group guard: the group must be one of this organization' using errcode = 'check_violation';
    end if;
    if new.member_membership_id is not null then
        if not exists (select 1 from membership m where m.id = new.member_membership_id
                          and m.tenant_id = new.tenant_id and m.status = 'ACTIVE' and m.deleted_at is null) then
            raise exception 'group guard: only an active member of this organization can be in a group'
                using errcode = 'check_violation';
        end if;
    else
        if not exists (select 1 from public_group g where g.id = new.member_group_id
                          and g.tenant_id = new.tenant_id and g.deleted_at is null) then
            raise exception 'group guard: a group in a group must be a group of this organization'
                using errcode = 'check_violation';
        end if;
        -- Adding group C to group G makes a loop exactly when G is C or is reachable by going down from C.
        if exists (
                with recursive below(id) as (
                    select new.member_group_id
                    union
                    select gm.member_group_id
                      from public_group_member gm
                      join below b on gm.group_id = b.id
                     where gm.member_group_id is not null and gm.deleted_at is null and gm.tenant_id = new.tenant_id
                )
                select 1 from below where id = new.group_id) then
            raise exception 'group guard: a group cannot contain itself, directly or through other groups'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_group_member_guard() is
    'A group member is an active member or a group of the same organization, and groups never form a loop; checked under one lock per organization (ADR-0047).';

-- An access policy given to a group: both are live rows of this organization, and the policy needs no licence (a
-- licence belongs to a person, so a licence-bound policy is given to people one by one).
create function platform_group_policy_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.group_id is distinct from old.group_id
            or new.access_policy_id is distinct from old.access_policy_id) then
        raise exception 'group guard: a group policy does not change its group or its policy'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is null then
        if not exists (select 1 from public_group g
                        where g.id = new.group_id and g.tenant_id = new.tenant_id and g.deleted_at is null) then
            raise exception 'group guard: the group must be one of this organization'
                using errcode = 'check_violation';
        end if;
        if not exists (select 1 from access_policy p
                        where p.id = new.access_policy_id and p.tenant_id = new.tenant_id and p.deleted_at is null) then
            raise exception 'group guard: the access policy must be one of this organization'
                using errcode = 'check_violation';
        end if;
        if tg_op = 'INSERT' and exists (select 1 from access_policy p where p.id = new.access_policy_id
                and p.required_licence_type_id is not null) then
            raise exception 'group guard: an access policy that needs a licence cannot be given to a group'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_group_policy_guard() is
    'An access policy given to a group is a policy of the same organization that needs no licence (ADR-0047).';

-- The policy guard of V020, now also: a policy that a group uses is in use.
create or replace function platform_access_policy_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        return new;
    end if;
    if new.required_licence_type_id is distinct from old.required_licence_type_id and (exists (
            select 1 from member_access_policy p
             where p.tenant_id = old.tenant_id and p.access_policy_id = old.id and p.deleted_at is null)
            or exists (select 1 from public_group_access_policy gp
                        where gp.tenant_id = old.tenant_id and gp.access_policy_id = old.id
                          and gp.deleted_at is null)) then
        raise exception 'access policy guard: a policy in use keeps its licence type' using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null and old.deleted_at is null and (exists (
            select 1 from member_access_policy p
             where p.tenant_id = old.tenant_id and p.access_policy_id = old.id and p.deleted_at is null)
            or exists (select 1 from public_group_access_policy gp
                        where gp.tenant_id = old.tenant_id and gp.access_policy_id = old.id
                          and gp.deleted_at is null)) then
        raise exception 'access policy guard: a policy in use cannot be removed' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_access_policy_guard() is
    'An access policy held by a member or a group is not removed and keeps the licence type it needs (ADR-0039, ADR-0047).';

-- ---------------------------------------------------------------------------------------------------------------
-- the last member who can manage access, now also through groups and with the licence rule of access policies
-- ---------------------------------------------------------------------------------------------------------------

-- A member HOLDS the ability when they are active and one of these gives it to them: their profile (only while they hold
-- the licence of its type), an access policy assigned to them (a licence-bound one only while they hold a licence of the
-- type it needs, which their profile's licence may be, ADR-0046), an access policy given to a group they are in, directly
-- or through nested groups (ADR-0047), or an individual grant.
create or replace function platform_access_holders(organization uuid) returns bigint
    language sql
    stable
as $count$
    select count(*)
      from membership m
     where m.tenant_id = organization and m.status = 'ACTIVE' and m.deleted_at is null
       and (
            exists (select 1
                      from member_access ma
                      join profile pr on pr.id = ma.profile_id and pr.tenant_id = ma.tenant_id
                                     and pr.deleted_at is null
                      join licence_assignment la on la.tenant_id = ma.tenant_id
                                                and la.membership_id = ma.membership_id
                                                and la.purpose = 'PROFILE' and la.deleted_at is null
                                                and la.licence_type_id = pr.licence_type_id
                     where ma.tenant_id = organization and ma.membership_id = m.id and ma.deleted_at is null
                       and (pr.full_access or 'access.manage' = any (pr.abilities)))
            or exists (select 1
                         from member_access_policy mp
                         join access_policy ap on ap.id = mp.access_policy_id and ap.tenant_id = mp.tenant_id
                                              and ap.deleted_at is null
                        where mp.tenant_id = organization and mp.membership_id = m.id and mp.deleted_at is null
                          and 'access.manage' = any (ap.abilities)
                          and (ap.required_licence_type_id is null
                               or exists (select 1 from licence_assignment la
                                           where la.tenant_id = organization and la.membership_id = m.id
                                             and la.deleted_at is null
                                             and la.licence_type_id = ap.required_licence_type_id)))
            or exists (select 1
                         from member_grant g
                        where g.tenant_id = organization and g.membership_id = m.id and g.deleted_at is null
                          and g.ability = 'access.manage')
            or exists (
                with recursive groups_of(id) as (
                    select gm.group_id
                      from public_group_member gm
                      join public_group pg on pg.id = gm.group_id and pg.deleted_at is null
                     where gm.tenant_id = organization and gm.member_membership_id = m.id and gm.deleted_at is null
                    union
                    select gm.group_id
                      from public_group_member gm
                      join groups_of g on gm.member_group_id = g.id
                      join public_group pg on pg.id = gm.group_id and pg.deleted_at is null
                     where gm.tenant_id = organization and gm.deleted_at is null
                )
                select 1
                  from groups_of g
                  join public_group_access_policy gp on gp.group_id = g.id and gp.tenant_id = organization
                                                    and gp.deleted_at is null
                  join access_policy ap on ap.id = gp.access_policy_id and ap.tenant_id = gp.tenant_id
                                       and ap.deleted_at is null
                 where 'access.manage' = any (ap.abilities)));
$count$;

comment on function platform_access_holders(uuid) is
    'How many active members of an organization hold the ability access.manage through a licensed profile, an access policy (licensed when it needs a licence), an access policy of a group they are in, or an individual grant (ADR-0044, ADR-0046, ADR-0047).';

-- ---------------------------------------------------------------------------------------------------------------
-- triggers, row level security, comments
-- ---------------------------------------------------------------------------------------------------------------

create trigger public_group_row_guard before insert or update on public_group
    for each row execute function platform_row_guard();
create trigger public_group_tenant_guard before update on public_group
    for each row execute function platform_tenant_guard();
create trigger public_group_access_guard_before before insert or update or delete on public_group
    for each row execute function platform_access_guard_before();
create constraint trigger public_group_access_guard_check after insert or update or delete on public_group
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger public_group_member_row_guard before insert or update on public_group_member
    for each row execute function platform_row_guard();
create trigger public_group_member_tenant_guard before update on public_group_member
    for each row execute function platform_tenant_guard();
create trigger public_group_member_access_guard_before before insert or update or delete on public_group_member
    for each row execute function platform_access_guard_before();
create trigger public_group_member_member_guard before insert or update on public_group_member
    for each row execute function platform_group_member_guard();
create constraint trigger public_group_member_access_guard_check after insert or update or delete on public_group_member
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger public_group_access_policy_row_guard before insert or update on public_group_access_policy
    for each row execute function platform_row_guard();
create trigger public_group_access_policy_tenant_guard before update on public_group_access_policy
    for each row execute function platform_tenant_guard();
create trigger public_group_access_policy_access_guard_before
    before insert or update or delete on public_group_access_policy
    for each row execute function platform_access_guard_before();
create trigger public_group_access_policy_policy_guard before insert or update on public_group_access_policy
    for each row execute function platform_group_policy_guard();
create constraint trigger public_group_access_policy_access_guard_check
    after insert or update or delete on public_group_access_policy
    deferrable initially deferred for each row execute function platform_access_guard_check();

alter table public_group enable row level security;
alter table public_group force row level security;
create policy public_group_insert on public_group for insert with check (tenant_id = (select platform_current_tenant()));
create policy public_group_select on public_group for select using (tenant_id = (select platform_current_tenant()));
create policy public_group_update on public_group for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy public_group_delete on public_group for delete using (tenant_id = (select platform_current_tenant()));

alter table public_group_member enable row level security;
alter table public_group_member force row level security;
create policy public_group_member_insert on public_group_member for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy public_group_member_select on public_group_member for select
    using (tenant_id = (select platform_current_tenant()));
create policy public_group_member_update on public_group_member for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy public_group_member_delete on public_group_member for delete
    using (tenant_id = (select platform_current_tenant()));

alter table public_group_access_policy enable row level security;
alter table public_group_access_policy force row level security;
create policy public_group_access_policy_insert on public_group_access_policy for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy public_group_access_policy_select on public_group_access_policy for select
    using (tenant_id = (select platform_current_tenant()));
create policy public_group_access_policy_update on public_group_access_policy for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy public_group_access_policy_delete on public_group_access_policy for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table public_group is
    'A named set of people and groups of one organization; can be given access policies. Tenant-scoped (ADR-0015, ADR-0047).';
comment on table public_group_member is
    'A person or a group that is a member of a group; groups form no loop. Tenant-scoped (ADR-0015, ADR-0047).';
comment on table public_group_access_policy is
    'An access policy given to a group; its members hold what the policy gives. Tenant-scoped (ADR-0015, ADR-0047).';
