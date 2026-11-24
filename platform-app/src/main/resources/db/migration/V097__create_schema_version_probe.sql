-- The schema version probe (Sprint 33, ADR-0288). Expand only: one new function, nothing removed or renamed, so the
-- release before this one keeps working on the migrated database (that is the rollback rule).
--
-- WHY IT EXISTS. In a deployment the migrations run in a job of their own, as the owner role, before the new release
-- starts (ADR-0288). The running application must then refuse to start on a schema that is behind its release, with one
-- clear sentence, instead of failing on the first statement that needs a table the job has not made yet. To ask "which
-- version is the schema at?" the application would have to read the migration tool's history table, and the application
-- role is deliberately given no access at all to that table (M001, ADR-0009): it may not learn what was migrated, only
-- what it needs. This function answers that one question and nothing else, with the rights of the owner who made it.
--
-- WHAT IT RETURNS. The highest successfully applied migration version as a whole number (0 when none), the same number
-- that is in the name of the file (V097 is 97). It never returns a script name, a checksum, a time or a user.
--
-- WHO MAY CALL IT. PostgreSQL gives a new function to everybody; that is intended here and not narrowed: the number says
-- nothing a person who can connect could not learn from the release notes. The function does not take an argument, so
-- there is nothing to inject, and its search path is fixed so it cannot be made to read another table of that name.

create function platform_schema_version() returns integer
    language sql
    stable
    security definer
    set search_path = public, pg_temp
as $version$
    select coalesce(max(version::integer), 0)
      from public.flyway_schema_history
     where success and version is not null and version ~ '^[0-9]+$';
$version$;

comment on function platform_schema_version() is
    'The highest applied migration version as a whole number; asked by the application at start-up, which has no access to the history table (ADR-0288).';
