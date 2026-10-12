-- S2: read a record, then update it with the version that was read (optimistic concurrency).
\set i random(1, :big_n)
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select version as v from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i) and deleted_at is null;
\gset
update object_record set payload = payload || jsonb_build_object('Notes__c', 'updated ' || :v),
                         version = :v + 1, updated_by = bench_tenant(0)
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and id = bench_id(:i)
   and version = :v and deleted_at is null;
commit;
