-- S4 (a): filter on a field that is NOT indexed, with a value that matches one record in ten (ends early).
\set r random(0, 9)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Case__c' and deleted_at is null
   and (payload ->> 'Region__c') = 'R' || (:r)::text
 order by id limit 50;
commit;
