-- The count that decides whether a scan is allowed (ADR-0105): at most 20,001 live records of the object are counted.
begin;
select set_config('application_name', 'bench', true);
select set_config('app.current_tenant', bench_tenant(1)::text, true);
select count(*) from (select 1 from object_record
 where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null limit 20001) live;
commit;
