-- MANUAL MIGRATION M002: the first platform administrator (Sprint 6, ADR-0030).
--
-- WHAT IT DOES
--   Gives the PLATFORM_ADMIN role to ONE existing account, named by its e-mail address, and writes an audit record
--   saying so. The platform has no default account and no built-in password: the very first person who may operate the
--   platform (create organizations for clients, suspend them, manage plans) is chosen by a human running this script.
--   Every later platform administrator is granted from the platform console by an existing one (and audited there).
--
-- BEFORE YOU RUN IT
--   1. The person must already have an ordinary account: sign up on the platform's own address with the e-mail and
--      choose a name and a password. The script refuses an address with no active account.
--   2. Use an account whose mailbox only that person controls, and a long password of its own. Until multi-factor
--      sign-in exists (a launch gate, docs/production-transition-plan.md) this password is what protects the most
--      powerful door of the platform.
--
-- WHO RUNS IT, WHERE AND WHEN
--   Once per environment, as the OWNER role (the one named in PLATFORM_DB_OWNER_USER), after the application has started
--   once (so the migrations have created the tables). Not by the application role: it cannot, on purpose.
--
--     psql "host=<host> dbname=<database> user=<owner role>" -v ON_ERROR_STOP=1 -v admin_email=person@example.test \
--          -f M002__grant_first_platform_administrator.sql
--
--   The script stops with a message when the address has no active account, and when a platform administrator
--   already exists (add further ones from the console). To repair a platform whose administrators are all locked out,
--   add  -v allow_additional=yes  to the command: it then grants the role even though one exists.
--
-- CHECK AFTERWARDS (as the owner)
--     select a.role, u.email from platform_role_assignment a join platform_user u on u.id = a.user_id
--      where a.deleted_at is null;
--     select occurred_at, event_type, attributes from audit_record where event_type = 'platform.role.granted'
--      order by occurred_at desc limit 1;
--   Then sign in on the platform's own address (not an organization's) with that account.

\if :{?admin_email}
\else
    \echo 'M002 stopped: pass the address with  -v admin_email=person@example.test'
    \quit
\endif

-- A scalar sub-select always returns one row; for an unknown account the value is null, which psql leaves unset.
select (select id from platform_user
         where email = lower(btrim(:'admin_email')) and status = 'ACTIVE' and deleted_at is null) as target_user
\gset

\if :{?target_user}
\else
    \echo 'M002 stopped: there is no active account with this address. The person must sign up first.'
    \quit
\endif

select count(*) > 0 as admin_exists
  from platform_role_assignment a join platform_user u on u.id = a.user_id
 where a.role = 'PLATFORM_ADMIN' and a.deleted_at is null and u.status = 'ACTIVE' and u.deleted_at is null
\gset

\if :admin_exists
    \if :{?allow_additional}
        \echo 'A platform administrator already exists; granting another because allow_additional was given.'
    \else
        \echo 'M002 stopped: a platform administrator already exists. Grant further ones from the console.'
        \quit
    \endif
\endif

begin;

insert into platform_role_assignment (user_id, role, created_by, updated_by)
values (:'target_user', 'PLATFORM_ADMIN', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000')
on conflict (user_id, role) where deleted_at is null do nothing;

insert into audit_record (event_type, outcome, actor_user_id, attributes, created_by, updated_by)
values ('platform.role.granted', 'SUCCESS', :'target_user',
        '{"role": "PLATFORM_ADMIN", "how": "manual_bootstrap"}'::jsonb,
        '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000');

commit;

\echo 'M002 done: the account now holds the PLATFORM_ADMIN role. Sign in on the platform address.'
