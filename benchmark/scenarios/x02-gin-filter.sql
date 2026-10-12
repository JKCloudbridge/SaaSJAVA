-- Diagnostic (not judged): the same kind of multi-field filter answered by a containment index on the whole payload.
-- Run only while the temporary index of run-benchmark.ps1 exists. The containment operator is not leakproof either.
\set d random(0, 49)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null
   and payload @> jsonb_build_object('Department__c', 'Dept ' || lpad((:d)::text, 2, '0'), 'Active__c', true)
 order by id limit 50;
commit;
