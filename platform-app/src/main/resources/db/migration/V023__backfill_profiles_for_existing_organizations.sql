-- Gives every organization that exists when profiles arrive (Sprint 7, ADR-0039, ADR-0045) its two system profiles and gives
-- every active member a profile, so that nobody who administered yesterday has fewer abilities today.
--
-- Per organization (every one that is not removed):
--   * the profile "Organization administrator" (licence type admin, all abilities, system-defined) and the profile
--     "Member" (licence type user, no abilities, system-defined and the default for new members);
--   * the admin pool is at least as large as the number of current administrators (the plan's number is never lowered;
--     an organization that has more administrators than the plan allows keeps them, and a platform administrator sees
--     and changes the pool in the console);
--   * every active member who carried the Sprint 5 administrator marker gets the administrator profile and an admin
--     licence in place of the user licence they held; every other active member gets the member profile and keeps the
--     licence they hold. Deactivated members get nothing (a returning member is given the default profile).
-- The marker column and the invitation flag stay, unread, until a later contract migration drops them.
--
-- The tables are tenant-scoped with forced row level security, which binds the owner too, and a migration has no tenant.
-- As in V013 and V018, forcing is switched off for these statements and back on in the same transaction. Every
-- statement joins on the tenant column, so nothing is ever computed across organizations. Running it on an empty
-- database changes nothing (no organization, no row). The guards of V020 to V022 stay on and are satisfied: pools are
-- raised before licences are given, and organizations had no holder of the ability before, so the commit check passes.

alter table profile no force row level security;
alter table member_access no force row level security;
alter table licence_pool no force row level security;
alter table licence_assignment no force row level security;
alter table membership no force row level security;

-- 1. The two system profiles of every organization.
insert into profile (tenant_id, name, description, licence_type_id, abilities, system_key, full_access, is_default,
                     created_by, updated_by)
select t.id, 'Organization administrator', 'Every ability. Cannot be changed or removed.', lt.id, '{}',
       'administrator', true, false,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from tenant t
  join licence_type lt on lt.key = 'admin' and lt.deleted_at is null
 where t.deleted_at is null
   and not exists (select 1 from profile p
                    where p.tenant_id = t.id and p.system_key = 'administrator' and p.deleted_at is null);

insert into profile (tenant_id, name, description, licence_type_id, abilities, system_key, full_access, is_default,
                     created_by, updated_by)
select t.id, 'Member', 'The default profile of a new member. No administrative abilities until you add some.',
       lt.id, '{}', 'member', false, true,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from tenant t
  join licence_type lt on lt.key = 'user' and lt.deleted_at is null
 where t.deleted_at is null
   and not exists (select 1 from profile p
                    where p.tenant_id = t.id and p.system_key = 'member' and p.deleted_at is null);

-- 2. The admin pool covers the administrators the organization already has.
insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by)
select m.tenant_id, lt.id, count(*)::integer,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from membership m
  join licence_type lt on lt.key = 'admin' and lt.deleted_at is null
 where m.administrator and m.status = 'ACTIVE' and m.deleted_at is null
   and not exists (select 1 from licence_pool lp
                    where lp.tenant_id = m.tenant_id and lp.licence_type_id = lt.id and lp.deleted_at is null)
 group by m.tenant_id, lt.id;

update licence_pool lp
   set quantity = needed.administrators, version = lp.version + 1,
       updated_by = '00000000-0000-0000-0000-000000000000'
  from (select m.tenant_id, count(*)::integer as administrators
          from membership m
         where m.administrator and m.status = 'ACTIVE' and m.deleted_at is null
         group by m.tenant_id) needed,
       licence_type lt
 where lp.tenant_id = needed.tenant_id and lp.licence_type_id = lt.id and lt.key = 'admin'
   and lp.deleted_at is null and lp.quantity < needed.administrators;

-- 3. Administrators swap their user licence for an admin licence.
update licence_assignment a
   set deleted_at = now(), deleted_by = '00000000-0000-0000-0000-000000000000', version = a.version + 1,
       updated_by = '00000000-0000-0000-0000-000000000000'
 where a.purpose = 'PROFILE' and a.deleted_at is null
   and exists (select 1 from membership m
                where m.id = a.membership_id and m.tenant_id = a.tenant_id and m.administrator
                  and m.status = 'ACTIVE' and m.deleted_at is null)
   and a.licence_type_id = (select id from licence_type where key = 'user' and deleted_at is null);

insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, updated_by)
select m.tenant_id, m.id, lt.id,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from membership m
  join licence_type lt on lt.key = 'admin' and lt.deleted_at is null
 where m.administrator and m.status = 'ACTIVE' and m.deleted_at is null
   and not exists (select 1 from licence_assignment a
                    where a.membership_id = m.id and a.purpose = 'PROFILE' and a.deleted_at is null);

-- 4. Every active member gets a profile.
insert into member_access (tenant_id, membership_id, profile_id, created_by, updated_by)
select m.tenant_id, m.id,
       (select p.id from profile p
         where p.tenant_id = m.tenant_id and p.deleted_at is null
           and p.system_key = case when m.administrator then 'administrator' else 'member' end),
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from membership m
 where m.status = 'ACTIVE' and m.deleted_at is null
   and exists (select 1 from tenant t where t.id = m.tenant_id and t.deleted_at is null)
   and not exists (select 1 from member_access ma where ma.membership_id = m.id and ma.deleted_at is null);

-- The guard of V022 checks at commit; a table with a pending check cannot be altered, and after the next statements
-- row level security binds the owner again (it would then see no rows). So the checks run now, while the rows are visible.
set constraints all immediate;

alter table profile force row level security;
alter table member_access force row level security;
alter table licence_pool force row level security;
alter table licence_assignment force row level security;
alter table membership force row level security;
