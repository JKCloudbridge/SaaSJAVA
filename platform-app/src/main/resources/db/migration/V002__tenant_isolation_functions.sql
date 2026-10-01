-- Tenant isolation building blocks (ADR-0003, ADR-0015).
--
-- Every tenant-scoped table (one with a tenant_id column) gets row level security with this shape:
--
--     alter table <table> enable row level security;
--     alter table <table> force row level security;
--     create policy tenant_isolation on <table>
--         using      (tenant_id = (select platform_current_tenant()))
--         with check (tenant_id = (select platform_current_tenant()));
--     create trigger <table>_tenant_guard before update on <table>
--         for each row execute function platform_tenant_guard();
--     create index <table>_tenant on <table> (tenant_id, ...);
--
-- The application sets the transaction-local setting app.current_tenant at the start of every transaction, from the
-- tenant context of the request, job or event. Without it the policy compares against null and matches no row:
-- code that forgets the tenant sees nothing, never everything. Migrations are forward-only and run as the owner role
-- (ADR-0009).

-- The tenant of the current transaction, or null. A setting that was never set, was reset by a finished transaction
-- (PostgreSQL then reports an empty string, not "unset") or holds anything that is not a UUID all mean "no tenant";
-- none of them raises an error, so no setting value can end up in an error message.
create function platform_current_tenant() returns uuid
    language sql stable parallel safe
as $function$
    select case
        when current_setting('app.current_tenant', true) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            then current_setting('app.current_tenant', true)::uuid
    end
$function$;

comment on function platform_current_tenant() is
    'The tenant of the current transaction (setting app.current_tenant), or null. Tenant policies compare against it, so null matches nothing (ADR-0015).';

-- Whether the current transaction runs inside a named system scope: platform work that deliberately spans tenants,
-- for example the outbox relay. A table's policy names the scopes it admits; it is never a general bypass.
create function platform_in_system_scope(scope text) returns boolean
    language sql stable parallel safe
as $function$
    select coalesce(current_setting('app.system_scope', true), '') = scope
$function$;

comment on function platform_in_system_scope(text) is
    'True when the transaction runs in the named system scope (setting app.system_scope). Used by the policies of tables that platform infrastructure must read across tenants (ADR-0015).';

-- A row never moves to another tenant: the owner of a row is part of its identity, like id.
create function platform_tenant_guard() returns trigger
    language plpgsql
as $guard$
begin
    if new.tenant_id is distinct from old.tenant_id then
        raise exception 'tenant guard: tenant_id of % is immutable', tg_table_name using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_tenant_guard() is
    'Refuses any update that changes tenant_id. Attached to every tenant-scoped table as <table>_tenant_guard (ADR-0015).';

-- The migration tool's history table is created by the tool, before any migration, so it can receive the default
-- privileges of the application role (manual migration M001). The application has no business with it: take every
-- right away from every role except the owner, whatever the application role is called.
do $history$
declare
    grantee_oid oid;
begin
    for grantee_oid in
        select distinct (aclexplode(c.relacl)).grantee
          from pg_class c
         where c.oid = 'public.flyway_schema_history'::regclass
    loop
        if grantee_oid <> 0 and grantee_oid <> (select relowner from pg_class
                                                 where oid = 'public.flyway_schema_history'::regclass) then
            execute format('revoke all on table public.flyway_schema_history from %I',
                           pg_get_userbyid(grantee_oid));
        end if;
    end loop;
end
$history$;
