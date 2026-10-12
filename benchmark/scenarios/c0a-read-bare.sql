-- Carried note (Sprint 2), step 1 of 3: the read of S1 with NO setting statements (run as the role that bypasses row
-- level security, because without the tenant setting the policy would show nothing).
\set i random(1, :big_n)
begin;
select id, version, owner_id, record_type, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and deleted_at is null;
commit;
