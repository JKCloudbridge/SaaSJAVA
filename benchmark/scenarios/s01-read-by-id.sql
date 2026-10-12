-- S1: read one record by identifier. The transaction starts as the application's does (application name, tenant).
\set i random(1, :big_n)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, owner_id, record_type, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and deleted_at is null;
commit;
