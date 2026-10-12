-- S3 number: a range on an indexed number field, sorted by that field then identifier, one page of 50.
\set s random(30000, 118000)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select id, version, payload from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null
   and s_num_1 between :s and :s + 200
 order by s_num_1, id limit 50;
commit;
