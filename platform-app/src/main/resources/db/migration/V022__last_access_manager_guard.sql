-- The last member who can manage access stays (Sprint 7, ADR-0044). It replaces the "last administrator" rule of Sprint 5,
-- which was about the changeable administrator marker.
--
-- Why this ability: whoever holds 'access.manage' can give every other ability to anybody, so an organization that always
-- has one holder can always repair itself; one that loses the last holder is locked out for good.
--
-- A member HOLDS the ability when they are active and one of these gives it to them: their profile (only while they hold
-- the licence of the profile's type, ADR-0039), an access policy assigned to them, or an individual grant.
-- The key 'access.manage' is the one of Ability.ACCESS_MANAGE in the code; a test keeps the two equal.
--
-- How it works. Several tables decide who holds the ability, and a service changes more than one of them in one
-- transaction (replace a profile: remove a row, add a row), so the rule is checked once, at commit: "the organization had
-- at least one holder when this transaction first touched these tables, and has none now" is refused. A BEFORE trigger
-- on each table takes one lock per organization and remembers the count of holders as it was (so two transactions that
-- each remove a different holder are decided one after the other, the second one sees the first one's result), and a
-- trigger that runs at commit compares. Organizations that never had a holder (being set up, or a test fixture) are not
-- blocked: nothing is lost that was not there.

create function platform_access_holders(organization uuid) returns bigint
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
                          and 'access.manage' = any (ap.abilities))
            or exists (select 1
                         from member_grant g
                        where g.tenant_id = organization and g.membership_id = m.id and g.deleted_at is null
                          and g.ability = 'access.manage'));
$count$;

comment on function platform_access_holders(uuid) is
    'How many active members of an organization hold the ability access.manage through a licensed profile, an access policy or an individual grant (ADR-0044).';

create function platform_access_guard_before() returns trigger
    language plpgsql
as $guard$
declare
    organization uuid;
    remembered text;
begin
    organization := coalesce(new.tenant_id, old.tenant_id);
    remembered := 'platform.access_holders_before.t' || replace(organization::text, '-', '_');
    if coalesce(current_setting(remembered, true), '') = '' then
        perform pg_advisory_xact_lock(hashtextextended('access-managers:' || organization::text, 0));
        perform set_config(remembered, platform_access_holders(organization)::text, true);
    end if;
    return coalesce(new, old);
end;
$guard$;

create function platform_access_guard_check() returns trigger
    language plpgsql
as $guard$
declare
    organization uuid;
    before_count text;
begin
    organization := coalesce(new.tenant_id, old.tenant_id);
    before_count := coalesce(current_setting('platform.access_holders_before.t'
        || replace(organization::text, '-', '_'), true), '');
    if before_count <> '' and before_count::bigint >= 1 and platform_access_holders(organization) < 1 then
        raise exception 'access guard: the last member who can manage access cannot be removed'
            using errcode = 'check_violation';
    end if;
    return null;
end;
$guard$;

comment on function platform_access_guard_before() is
    'Takes the per-organization lock and remembers how many members could manage access before the transaction changed anything (ADR-0044).';
comment on function platform_access_guard_check() is
    'At commit: refuses a transaction that leaves an organization, which had a member who can manage access, with none (ADR-0044).';

-- Every table that decides who holds the ability. The BEFORE trigger names sort ahead of the licence pool lock.
create trigger membership_access_guard_before before update on membership
    for each row execute function platform_access_guard_before();
create constraint trigger membership_access_guard_check after update on membership
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger profile_access_guard_before before insert or update or delete on profile
    for each row execute function platform_access_guard_before();
create constraint trigger profile_access_guard_check after insert or update or delete on profile
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger access_policy_access_guard_before before insert or update or delete on access_policy
    for each row execute function platform_access_guard_before();
create constraint trigger access_policy_access_guard_check after insert or update or delete on access_policy
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger member_access_access_guard_before before insert or update or delete on member_access
    for each row execute function platform_access_guard_before();
create constraint trigger member_access_access_guard_check after insert or update or delete on member_access
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger member_access_policy_access_guard_before before insert or update or delete on member_access_policy
    for each row execute function platform_access_guard_before();
create constraint trigger member_access_policy_access_guard_check
    after insert or update or delete on member_access_policy
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger member_grant_access_guard_before before insert or update or delete on member_grant
    for each row execute function platform_access_guard_before();
create constraint trigger member_grant_access_guard_check after insert or update or delete on member_grant
    deferrable initially deferred for each row execute function platform_access_guard_check();

create trigger licence_assignment_access_guard_before before insert or update or delete on licence_assignment
    for each row execute function platform_access_guard_before();
create constraint trigger licence_assignment_access_guard_check
    after insert or update or delete on licence_assignment
    deferrable initially deferred for each row execute function platform_access_guard_check();

-- The membership guard of Sprint 5 without the rule about the administrator marker (the rule above replaces it): legal
-- status moves only. A returning member does not get an old marker back, and a leaving member loses it, so that rows
-- written before this sprint still satisfy the check that only an active member carries it.
create or replace function platform_membership_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'ACTIVE' then
            raise exception 'membership guard: a membership starts as ACTIVE' using errcode = 'check_violation';
        end if;
        return new;
    end if;

    if new.status <> old.status then
        if not ((old.status = 'ACTIVE' and new.status = 'DEACTIVATED')
                or (old.status = 'DEACTIVATED' and new.status = 'ACTIVE')) then
            raise exception 'membership guard: % to % is not a legal transition', old.status, new.status
                using errcode = 'check_violation';
        end if;
        new.status_changed_at := now();
    end if;
    if new.status <> 'ACTIVE' then
        new.administrator := false;
    end if;
    return new;
end;
$guard$;

comment on function platform_membership_guard() is
    'Enforces the membership lifecycle (ADR-0026): legal status moves. The last member who can manage access stays: see platform_access_guard_check (ADR-0044).';
