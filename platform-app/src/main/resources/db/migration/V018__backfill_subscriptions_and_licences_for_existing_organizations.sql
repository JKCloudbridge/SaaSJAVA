-- Gives every organization that exists when licensing arrives (Sprint 6, ADR-0033) a trial subscription and pools, and
-- gives its active members a licence, so nothing that worked yesterday is unlicensed today.
--
-- Organizations founded before this migration (the developer's own, the local demo organizations) have no subscription.
-- Each non-deactivated one starts a 30-day trial on the plan "trial" (the plan set up by V015). The "user" pool is the
-- plan's quantity or the number of active members, whichever is larger, and every active member holds a user licence:
-- existing members are never left without. The "admin" pool takes the plan's quantity and nobody holds one yet (the
-- licence of an administrator is assigned on purpose, Sprint 7 ties it to a profile).
--
-- The pools and assignments are tenant-scoped with forced row level security, which binds the owner too, and a
-- migration has no tenant. As in V013, forcing is switched off for these statements and back on in the same
-- transaction (membership too: the licence guard and the pool sizes read it). Every statement joins on the tenant
-- column, so nothing is ever computed across organizations.
-- Running it on an empty database changes nothing (no organization, no row).

alter table licence_pool no force row level security;
alter table licence_assignment no force row level security;
alter table membership no force row level security;

insert into subscription (bound_tenant_id, plan_id, status, started_at, trial_ends_at, created_by, updated_by)
select t.id, p.id, 'TRIAL', now(), now() + make_interval(days => p.trial_days),
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from tenant t
  cross join plan p
 where p.key = 'trial' and p.deleted_at is null and p.trial_days is not null
   and t.deleted_at is null and t.status <> 'DEACTIVATED'
   and not exists (select 1 from subscription s where s.bound_tenant_id = t.id and s.deleted_at is null);

insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by)
select t.id, lt.id,
       greatest(pl.quantity,
                case when lt.key = 'user'
                     then (select count(*) from membership m
                            where m.tenant_id = t.id and m.status = 'ACTIVE' and m.deleted_at is null)
                     else 0 end)::integer,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from tenant t
  join subscription s on s.bound_tenant_id = t.id and s.deleted_at is null
  join plan_licence pl on pl.plan_id = s.plan_id and pl.deleted_at is null
  join licence_type lt on lt.id = pl.licence_type_id and lt.deleted_at is null
 where t.deleted_at is null and t.status <> 'DEACTIVATED'
   and not exists (select 1 from licence_pool lp
                    where lp.tenant_id = t.id and lp.licence_type_id = lt.id and lp.deleted_at is null);

insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, updated_by)
select m.tenant_id, m.id, lt.id,
       '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'
  from membership m
  join tenant t on t.id = m.tenant_id and t.deleted_at is null and t.status <> 'DEACTIVATED'
  join licence_type lt on lt.key = 'user' and lt.deleted_at is null
 where m.status = 'ACTIVE' and m.deleted_at is null
   and exists (select 1 from licence_pool lp
                where lp.tenant_id = m.tenant_id and lp.licence_type_id = lt.id and lp.deleted_at is null)
   and not exists (select 1 from licence_assignment a
                    where a.membership_id = m.id and a.deleted_at is null);

alter table licence_pool force row level security;
alter table licence_assignment force row level security;
alter table membership force row level security;
