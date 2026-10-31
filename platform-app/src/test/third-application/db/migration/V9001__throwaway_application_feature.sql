-- Test only (FA13B, ADR-0226): the plan feature of the throwaway third application. It is on the classpath of
-- ThirdApplicationIT alone, never in the product.
insert into feature (key, name, created_by, updated_by)
values ('throwaway-application', 'Throwaway Application', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000');
