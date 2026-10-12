-- Diagnostic (not judged): the same filter as s03a, written against the JSON payload. Under row level security
-- PostgreSQL cannot use an index for it (the JSON field operator is not leakproof).
\set d random(0, 49)
\set i random(1, :big_n)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null
   and (payload ->> 'Department__c') = 'Dept ' || lpad((:d)::text, 2, '0')
   and id > bench_id(:i)
 order by id limit 50;
commit;
