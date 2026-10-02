-- Licence types get a kind, and an access policy that needs the same licence type as the member's profile uses no extra
-- licence (Sprint 8, ADR-0046).
--
-- 1. kind. A licence type is either a SEAT (the right to occupy a seat: a profile belongs to one, 'user' and 'admin'
--    are seats) or an ADD_ON (sold on top, for example the licence of a standard access policy). Only a seat can be the
--    licence type of a profile; an access policy may need any type. The two existing types are seats.
-- 2. The licence rule of access policies. A member holds one licence for their profile. A licence-bound access policy
--    whose licence type is the SAME as the profile's needs no licence of its own (the profile's licence covers it);
--    one of a DIFFERENT type takes one licence of that type from the pool. Until now every licence-bound policy took
--    one of its own, so rows that the new rule does not need are released here (only where the member also holds the
--    licence for their profile of that type; a member without a profile licence keeps the row, which then is the
--    licence of that type they hold).
--
-- licence_type is platform-level (no tenant column, no row level security): the catalogue is the vendor's. The
-- assignment table is tenant-scoped with forced row level security, which binds the owner too, and a migration has no
-- tenant: as in V013, V018 and V023, forcing is switched off for this one statement and back on in the same
-- transaction; the statement joins on the tenant column, so nothing is computed across organizations.

-- ---------------------------------------------------------------------------------------------------------------
-- 1. kind
-- ---------------------------------------------------------------------------------------------------------------

alter table licence_type add column kind text not null default 'SEAT';
alter table licence_type add constraint licence_type_kind_known check (kind in ('SEAT', 'ADD_ON'));

comment on column licence_type.kind is
    'SEAT: the right to occupy a seat (a profile belongs to a seat type). ADD_ON: sold on top, for example the licence of a standard access policy (ADR-0046).';

-- The profile guard of V020, now also: the licence type of a profile is a seat.
create or replace function platform_profile_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if not exists (select 1 from licence_type where id = new.licence_type_id and deleted_at is null
                          and kind = 'SEAT') then
            raise exception 'profile guard: the licence type of a profile must be a seat'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.system_key is distinct from old.system_key or new.full_access is distinct from old.full_access then
        raise exception 'profile guard: a system profile keeps its kind' using errcode = 'check_violation';
    end if;
    if new.licence_type_id is distinct from old.licence_type_id and not exists (
            select 1 from licence_type where id = new.licence_type_id and deleted_at is null and kind = 'SEAT') then
        raise exception 'profile guard: the licence type of a profile must be a seat'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null and old.deleted_at is null then
        if old.system_key is not null then
            raise exception 'profile guard: a system profile cannot be removed' using errcode = 'check_violation';
        end if;
        if old.is_default then
            raise exception 'profile guard: the default profile cannot be removed' using errcode = 'check_violation';
        end if;
        if exists (select 1 from member_access ma
                    where ma.tenant_id = old.tenant_id and ma.profile_id = old.id and ma.deleted_at is null) then
            raise exception 'profile guard: a profile in use cannot be removed' using errcode = 'check_violation';
        end if;
    end if;
    if new.licence_type_id is distinct from old.licence_type_id and exists (
            select 1 from member_access ma
             where ma.tenant_id = old.tenant_id and ma.profile_id = old.id and ma.deleted_at is null) then
        raise exception 'profile guard: a profile in use keeps its licence type' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_profile_guard() is
    'System profiles stay and keep their kind, the default profile stays, a profile in use is not removed and keeps its licence type, and the licence type of a profile is a seat (ADR-0039, ADR-0046).';

-- ---------------------------------------------------------------------------------------------------------------
-- 2. policy licences that the profile's licence already covers
-- ---------------------------------------------------------------------------------------------------------------

alter table licence_assignment no force row level security;

update licence_assignment redundant
   set deleted_at = now(), deleted_by = '00000000-0000-0000-0000-000000000000', version = redundant.version + 1,
       updated_by = '00000000-0000-0000-0000-000000000000'
 where redundant.purpose = 'ACCESS_POLICY' and redundant.deleted_at is null
   and exists (select 1 from licence_assignment own
                where own.tenant_id = redundant.tenant_id and own.membership_id = redundant.membership_id
                  and own.purpose = 'PROFILE' and own.deleted_at is null
                  and own.licence_type_id = redundant.licence_type_id);

-- The commit-time guard of V022 is pending on the rows just changed, and a table with a pending check cannot be
-- altered: run the checks now (as in V023), then force row level security again.
set constraints all immediate;

alter table licence_assignment force row level security;
