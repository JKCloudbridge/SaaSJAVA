-- Carried note (Sprint 5): the membership check made on every request of an organization host, in the tenant
-- transaction: the two setting statements, the membership of the person, and the read of the security version.
\set n random(1, :users)
\if :n % 10 = 0
\set k 1
\else
\set k 2 + :n % :small_t
\endif
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(:k)::text, true);
select id, user_id, status from membership where user_id = bench_id(300000000 + :n) and deleted_at is null;
select coalesce((select version from security_version where deleted_at is null), -1) as version,
       pg_current_xact_id_if_assigned() is not null as wrote;
commit;
