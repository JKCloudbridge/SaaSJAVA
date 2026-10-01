-- MANUAL MIGRATION M001: the application database role (ADR-0003, ADR-0009, ADR-0015).
--
-- WHAT IT DOES
--   Creates the login role "platform_app" that the running application connects as, and gives it exactly what it needs:
--   connect to this database, use the public schema, and read and write the tables the migrations create.
--   The role is deliberately weak, because row level security only protects tenants when the application cannot
--   get around it:
--     * not a superuser, cannot create databases or roles, does not bypass row level security;
--     * owns nothing and cannot create or change anything (no schema rights);
--     * no TRUNCATE (it ignores row level security), no REFERENCES, no TRIGGER on any table;
--     * no access at all to the migration tool's history table.
--   Tables created later by the migrations are covered automatically through default privileges, so no migration
--   has to grant anything to the role.
--
-- THE PASSWORD IS NOT IN THIS FILE and never goes into any file. The role is created without one, so it cannot log
-- in until you set it (step 2 below). Use a long random value and store it only in the secrets store of the
-- environment (the application reads it from PLATFORM_DB_APP_PASSWORD).
--
-- WHO RUNS IT, WHERE AND WHEN
--   Once per environment (local, test, staging, production), in the platform database, BEFORE the application is
--   started for the first time, or at any later time (running it again is safe and repairs the grants).
--   Connect as the OWNER role, the one named in PLATFORM_DB_OWNER_USER, because "default privileges" apply to the
--   objects the role that runs this script will create. The owner needs the CREATEROLE attribute for the first run
--   (or an administrator creates the empty login role first:  create role platform_app login;  and then this script
--   only adjusts and grants). The script stops with a message when it is run as someone else after the migration
--   history exists.
--
--   1. psql "host=<host> dbname=<database> user=<owner role>" -v ON_ERROR_STOP=1 -f M001__create_application_role.sql
--   2. In the same psql session type:  \password platform_app     (psql asks for the password twice and never
--      shows it, and it does not reach the shell history or the server log)
--   3. Check (as the owner):
--        select rolname, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolcanlogin
--          from pg_roles where rolname = 'platform_app';          -- only rolcanlogin is true
--
-- A deployment may rename the role afterwards (alter role platform_app rename to ...): privileges follow the role,
-- not its name. Set PLATFORM_DB_APP_USER to the new name.

do $role$
declare
    owner_name constant text := current_user;
    history_owner text;
begin
    -- Default privileges below belong to the role running this script; they must be the role that creates the tables.
    select pg_get_userbyid(c.relowner) into history_owner
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'flyway_schema_history';
    if history_owner is not null and history_owner <> owner_name then
        raise exception 'M001 must run as the owner role %, not as %', history_owner, owner_name;
    end if;

    if not exists (select 1 from pg_roles where rolname = 'platform_app') then
        create role platform_app login;
    end if;
    -- Enforced on every run, so a role that was created by hand with too much power is corrected.
    alter role platform_app nosuperuser nocreatedb nocreaterole noinherit nobypassrls noreplication;

    execute format('grant connect on database %I to platform_app', current_database());
end
$role$;

grant usage on schema public to platform_app;
revoke create on schema public from platform_app;

-- Tables and sequences that the owner creates from now on.
alter default privileges in schema public grant select, insert, update, delete on tables to platform_app;
alter default privileges in schema public grant usage, select on sequences to platform_app;

-- Objects that already exist (matters when the migrations ran before this script).
grant select, insert, update, delete on all tables in schema public to platform_app;
grant usage, select on all sequences in schema public to platform_app;

-- The migration tool's own history is not application data: the application never reads or changes it.
do $history$
begin
    if to_regclass('public.flyway_schema_history') is not null then
        revoke all on table public.flyway_schema_history from platform_app;
    end if;
end
$history$;
